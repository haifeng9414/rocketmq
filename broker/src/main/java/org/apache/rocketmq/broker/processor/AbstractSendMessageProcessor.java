/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.rocketmq.broker.processor;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.List;
import java.util.Map;
import java.util.Random;

import io.netty.channel.ChannelHandlerContext;
import org.apache.rocketmq.broker.BrokerController;
import org.apache.rocketmq.broker.mqtrace.SendMessageContext;
import org.apache.rocketmq.broker.mqtrace.SendMessageHook;
import org.apache.rocketmq.common.MixAll;
import org.apache.rocketmq.common.TopicConfig;
import org.apache.rocketmq.common.TopicFilterType;
import org.apache.rocketmq.common.constant.DBMsgConstants;
import org.apache.rocketmq.common.constant.LoggerName;
import org.apache.rocketmq.common.constant.PermName;
import org.apache.rocketmq.common.help.FAQUrl;
import org.apache.rocketmq.common.message.MessageAccessor;
import org.apache.rocketmq.common.message.MessageConst;
import org.apache.rocketmq.common.message.MessageDecoder;
import org.apache.rocketmq.common.protocol.NamespaceUtil;
import org.apache.rocketmq.common.protocol.RequestCode;
import org.apache.rocketmq.common.protocol.ResponseCode;
import org.apache.rocketmq.common.protocol.header.SendMessageRequestHeader;
import org.apache.rocketmq.common.protocol.header.SendMessageRequestHeaderV2;
import org.apache.rocketmq.common.protocol.header.SendMessageResponseHeader;
import org.apache.rocketmq.common.sysflag.MessageSysFlag;
import org.apache.rocketmq.common.sysflag.TopicSysFlag;
import org.apache.rocketmq.common.utils.ChannelUtil;
import org.apache.rocketmq.logging.InternalLogger;
import org.apache.rocketmq.logging.InternalLoggerFactory;
import org.apache.rocketmq.remoting.common.RemotingHelper;
import org.apache.rocketmq.remoting.exception.RemotingCommandException;
import org.apache.rocketmq.remoting.netty.NettyRequestProcessor;
import org.apache.rocketmq.remoting.protocol.RemotingCommand;
import org.apache.rocketmq.store.MessageExtBrokerInner;

public abstract class AbstractSendMessageProcessor implements NettyRequestProcessor {
    protected static final InternalLogger log = InternalLoggerFactory.getLogger(LoggerName.BROKER_LOGGER_NAME);

    protected final static int DLQ_NUMS_PER_GROUP = 1;
    protected final BrokerController brokerController;
    protected final Random random = new Random(System.currentTimeMillis());
    protected final SocketAddress storeHost;
    // 调用sendMessage或sendBatchMessage方法处理发送消息的请求时的hook
    private List<SendMessageHook> sendMessageHookList;

    public AbstractSendMessageProcessor(final BrokerController brokerController) {
        this.brokerController = brokerController;
        this.storeHost =
            new InetSocketAddress(brokerController.getBrokerConfig().getBrokerIP1(), brokerController
                .getNettyServerConfig().getListenPort());
    }

    // 返回值SendMessageContext保存了requestHeader的大部分属性
    protected SendMessageContext buildMsgContext(ChannelHandlerContext ctx,
        SendMessageRequestHeader requestHeader) {
        // 如果不存在SendMessageHook则不需要创建SendMessageContext，因为这里创建的SendMessageContext对象就是SendMessageHook
        // 对象执行方法时的参数
        if (!this.hasSendMessageHook()) {
            return null;
        }
        // 获取请求的topic
        String namespace = NamespaceUtil.getNamespaceFromResource(requestHeader.getTopic());
        SendMessageContext mqtraceContext;
        mqtraceContext = new SendMessageContext();
        // 设置请求的product group
        mqtraceContext.setProducerGroup(requestHeader.getProducerGroup());
        mqtraceContext.setNamespace(namespace);
        mqtraceContext.setTopic(requestHeader.getTopic());
        // 设置请求的属性
        mqtraceContext.setMsgProps(requestHeader.getProperties());
        // 设置channel目标服务器地址
        mqtraceContext.setBornHost(RemotingHelper.parseChannelRemoteAddr(ctx.channel()));
        // 设置broker的地址
        mqtraceContext.setBrokerAddr(this.brokerController.getBrokerAddr());
        // 设置borker所在的regionId
        mqtraceContext.setBrokerRegionId(this.brokerController.getBrokerConfig().getRegionId());
        // 设置requestHeader对象被创建的时间
        mqtraceContext.setBornTimeStamp(requestHeader.getBornTimestamp());

        // 添加两个属性到requestHeader.getProperties()
        Map<String, String> properties = MessageDecoder.string2messageProperties(requestHeader.getProperties());
        String uniqueKey = properties.get(MessageConst.PROPERTY_UNIQ_CLIENT_MESSAGE_ID_KEYIDX);
        properties.put(MessageConst.PROPERTY_MSG_REGION, this.brokerController.getBrokerConfig().getRegionId());
        properties.put(MessageConst.PROPERTY_TRACE_SWITCH, String.valueOf(this.brokerController.getBrokerConfig().isTraceOn()));
        requestHeader.setProperties(MessageDecoder.messageProperties2String(properties));

        if (uniqueKey == null) {
            uniqueKey = "";
        }
        // 设置requestHeader的properties中的UNIQ_KEY的值
        mqtraceContext.setMsgUniqueKey(uniqueKey);
        return mqtraceContext;
    }

    public boolean hasSendMessageHook() {
        return sendMessageHookList != null && !this.sendMessageHookList.isEmpty();
    }

    protected MessageExtBrokerInner buildInnerMsg(final ChannelHandlerContext ctx,
        final SendMessageRequestHeader requestHeader, final byte[] body, TopicConfig topicConfig) {
        int queueIdInt = requestHeader.getQueueId();
        if (queueIdInt < 0) {
            queueIdInt = Math.abs(this.random.nextInt() % 99999999) % topicConfig.getWriteQueueNums();
        }
        int sysFlag = requestHeader.getSysFlag();

        if (TopicFilterType.MULTI_TAG == topicConfig.getTopicFilterType()) {
            sysFlag |= MessageSysFlag.MULTI_TAGS_FLAG;
        }

        MessageExtBrokerInner msgInner = new MessageExtBrokerInner();
        msgInner.setTopic(requestHeader.getTopic());
        msgInner.setBody(body);
        msgInner.setFlag(requestHeader.getFlag());
        MessageAccessor.setProperties(msgInner,
            MessageDecoder.string2messageProperties(requestHeader.getProperties()));
        msgInner.setPropertiesString(requestHeader.getProperties());
        msgInner.setTagsCode(MessageExtBrokerInner.tagsString2tagsCode(topicConfig.getTopicFilterType(),
            msgInner.getTags()));

        msgInner.setQueueId(queueIdInt);
        msgInner.setSysFlag(sysFlag);
        msgInner.setBornTimestamp(requestHeader.getBornTimestamp());
        msgInner.setBornHost(ctx.channel().remoteAddress());
        msgInner.setStoreHost(this.getStoreHost());
        msgInner.setReconsumeTimes(requestHeader.getReconsumeTimes() == null ? 0 : requestHeader
            .getReconsumeTimes());
        return msgInner;
    }

    public SocketAddress getStoreHost() {
        return storeHost;
    }

    protected RemotingCommand msgContentCheck(final ChannelHandlerContext ctx,
        final SendMessageRequestHeader requestHeader, RemotingCommand request,
        final RemotingCommand response) {
        if (requestHeader.getTopic().length() > Byte.MAX_VALUE) {
            log.warn("putMessage message topic length too long {}", requestHeader.getTopic().length());
            response.setCode(ResponseCode.MESSAGE_ILLEGAL);
            return response;
        }
        if (requestHeader.getProperties() != null && requestHeader.getProperties().length() > Short.MAX_VALUE) {
            log.warn("putMessage message properties length too long {}", requestHeader.getProperties().length());
            response.setCode(ResponseCode.MESSAGE_ILLEGAL);
            return response;
        }
        if (request.getBody().length > DBMsgConstants.MAX_BODY_SIZE) {
            log.warn(" topic {}  msg body size {}  from {}", requestHeader.getTopic(),
                request.getBody().length, ChannelUtil.getRemoteIp(ctx.channel()));
            response.setRemark("msg body must be less 64KB");
            response.setCode(ResponseCode.MESSAGE_ILLEGAL);
            return response;
        }
        return response;
    }

    protected RemotingCommand msgCheck(final ChannelHandlerContext ctx,
        final SendMessageRequestHeader requestHeader, final RemotingCommand response) {
        // 检查broker的permission，是否支持写入
        if (!PermName.isWriteable(this.brokerController.getBrokerConfig().getBrokerPermission())
                /// todo: 意思是必须order topic的写权限配置才有效？
            && this.brokerController.getTopicConfigManager().isOrderTopic(requestHeader.getTopic())) {
            // 返回没有权限的error
            response.setCode(ResponseCode.NO_PERMISSION);
            response.setRemark("the broker[" + this.brokerController.getBrokerConfig().getBrokerIP1()
                + "] sending message is forbidden");
            return response;
        }
        // 如果topic的名字和系统保留的topic：TBW102名字相等则返回error
        if (!this.brokerController.getTopicConfigManager().isTopicCanSendMessage(requestHeader.getTopic())) {
            String errorMsg = "the topic[" + requestHeader.getTopic() + "] is conflict with system reserved words.";
            log.warn(errorMsg);
            response.setCode(ResponseCode.SYSTEM_ERROR);
            response.setRemark(errorMsg);
            return response;
        }

        /*
        正常情况下topic应该在broker下先手动创建好，producer和consumer才能使用该topic，否则producer或consumer无法从namesrv获取
        topic及broker的关联关系。但是RocketMQ也有一种自动创建topic的机制，该机制的工作方式是，如果broke的autoCreateTopicEnable配置
        为true，则broker的TopicConfigManager类的构造函数会将MixAll.AUTO_CREATE_TOPIC_KEY_TOPIC添加到其topicConfigTable属性。
        broker的BrokerController的start方法会在broker启动时调用registerBrokerAll方法，该方法会同步发送RequestCode.REGISTER_BROKER
        请求，将TopicConfigManager类的topicConfigTable属性的值注册到namesrv，注册的topicConfigTable属性中就包含MixAll.AUTO_CREATE_TOPIC_KEY_TOPIC
        这个topic，这使得所有autoCreateTopicEnable配置为true的broker都默认注册自己存在MixAll.AUTO_CREATE_TOPIC_KEY_TOPIC这个topic。

        当producer发送消息时需要从namesrv获取topic及broker的关联关系并根据自己的需要发送的消息的topic选择一个broker下的队列，如果消息
        的topic没有对应的broker，则producer会以MixAll.AUTO_CREATE_TOPIC_KEY_TOPIC作为topic向namesrv发送获取topic路由数据的请求，
        namesrv返回的路由数据实际上就是TopicRouteData对象，该对象包含了所有autoCreateTopicEnable配置为true的broker的地址。所以对于
        producer发送的没有创建topic的消息，实际上使用的是MixAll.AUTO_CREATE_TOPIC_KEY_TOPIC这个topic的路由信息，当某个autoCreateTopicEnable
        配置为true的broker接收到这种消息后，这里的获取到的topicConfig就是null，此时broker会以MixAll.AUTO_CREATE_TOPIC_KEY_TOPIC
        这个topic的配置为源，创建topic的配置，这样下次再收到这个topic的消息时就能直接获取到配置，而broker也会通过心跳告诉namesrv自己
        支持了之前那个不存在的topic

        测试的时候可以用这个特性，但是线上最好把autoCreateTopicEnable关掉，因为rocketmq在发送消息时，先去获取topic的路由信息，如果topic
        是第一次发送消息，由于namesrv没有topic的路由信息，所以会再次以“TBW102”这个默认topic获取路由信息，假设broker都开启了自动创建开关，
        那么此时会获取所有broker的路由信息，消息的发送会根据负载算法选择其中一台broker发送消息，消息到达broker后，发现本地没有该topic，
        会在这里创建该topic的信息并塞进本地缓存中，后台线程会将topic路由信息注册到namesrv中。如果其他生产者也发送这个不存在topic的消息，他们
        也都会在本地根据负载均衡选择一个broker，那么可能就会有多个broker支持不存在的topic，但是也有可能仅有很少的一部分broker支持，这取决于
        多少个broker收到那个不存在topic的消息。另外，由于所有的生产者都会定时从namesrv获取路由信息，如果其他生产者没有马上发送消息，在namesrv
        有了不存在的topic的路由信息后，其他生产者更新了这个路由信息，再发送消息，那么这样就会造成一个后果：以后所有该topic的消息，都将
        发送到路由信息指向的那个broker上，如果该topic消息量非常大，会造成某个broker上负载过大，这样消息的存储就达不到负载均衡的目的了。
        所以autoCreateTopicEnable开启的情况下，不存在的topic的路由是不可控的。
         */
        TopicConfig topicConfig =
            this.brokerController.getTopicConfigManager().selectTopicConfig(requestHeader.getTopic());
        if (null == topicConfig) {
            int topicSysFlag = 0;
            if (requestHeader.isUnitMode()) {
                if (requestHeader.getTopic().startsWith(MixAll.RETRY_GROUP_TOPIC_PREFIX)) {
                    topicSysFlag = TopicSysFlag.buildSysFlag(false, true);
                } else {
                    topicSysFlag = TopicSysFlag.buildSysFlag(true, false);
                }
            }

            log.warn("the topic {} not exist, producer: {}", requestHeader.getTopic(), ctx.channel().remoteAddress());
            // 以MixAll.AUTO_CREATE_TOPIC_KEY_TOPIC这个topic的配置为模版为当前topic创建配置
            topicConfig = this.brokerController.getTopicConfigManager().createTopicInSendMessageMethod(
                requestHeader.getTopic(),
                requestHeader.getDefaultTopic(),
                RemotingHelper.parseChannelRemoteAddr(ctx.channel()),
                requestHeader.getDefaultTopicQueueNums(), topicSysFlag);

            if (null == topicConfig) {
                if (requestHeader.getTopic().startsWith(MixAll.RETRY_GROUP_TOPIC_PREFIX)) {
                    topicConfig =
                        this.brokerController.getTopicConfigManager().createTopicInSendMessageBackMethod(
                            requestHeader.getTopic(), 1, PermName.PERM_WRITE | PermName.PERM_READ,
                            topicSysFlag);
                }
            }

            if (null == topicConfig) {
                response.setCode(ResponseCode.TOPIC_NOT_EXIST);
                response.setRemark("topic[" + requestHeader.getTopic() + "] not exist, apply first please!"
                    + FAQUrl.suggestTodo(FAQUrl.APPLY_TOPIC_URL));
                return response;
            }
        }

        // 从MQClientInstance类的topicRouteData2TopicPublishInfo方法可知，queueIdInt是创建MessageQueue时的循环索引值，该值
        // 的范围肯定是0~QueueData类的writeQueueNums值 - 1之间，也就是0~topicConfig.getWriteQueueNums() - 1之间，当然对于消费者
        // 的情况，那就是0~topicConfig.getReadQueueNums() - 1
        int queueIdInt = requestHeader.getQueueId();
        int idValid = Math.max(topicConfig.getWriteQueueNums(), topicConfig.getReadQueueNums());
        // 超出合法值则报错
        if (queueIdInt >= idValid) {
            String errorInfo = String.format("request queueId[%d] is illegal, %s Producer: %s",
                queueIdInt,
                topicConfig.toString(),
                RemotingHelper.parseChannelRemoteAddr(ctx.channel()));

            log.warn(errorInfo);
            response.setCode(ResponseCode.SYSTEM_ERROR);
            response.setRemark(errorInfo);

            return response;
        }
        return response;
    }

    public void registerSendMessageHook(List<SendMessageHook> sendMessageHookList) {
        this.sendMessageHookList = sendMessageHookList;
    }

    protected void doResponse(ChannelHandlerContext ctx, RemotingCommand request,
        final RemotingCommand response) {
        if (!request.isOnewayRPC()) {
            try {
                ctx.writeAndFlush(response);
            } catch (Throwable e) {
                log.error("SendMessageProcessor process request over, but response failed", e);
                log.error(request.toString());
                log.error(response.toString());
            }
        }
    }

    public void executeSendMessageHookBefore(final ChannelHandlerContext ctx, final RemotingCommand request,
        SendMessageContext context) {
        if (hasSendMessageHook()) {
            for (SendMessageHook hook : this.sendMessageHookList) {
                try {
                    final SendMessageRequestHeader requestHeader = parseRequestHeader(request);

                    String namespace = NamespaceUtil.getNamespaceFromResource(requestHeader.getTopic());
                    if (null != requestHeader) {
                        context.setNamespace(namespace);
                        context.setProducerGroup(requestHeader.getProducerGroup());
                        context.setTopic(requestHeader.getTopic());
                        context.setBodyLength(request.getBody().length);
                        context.setMsgProps(requestHeader.getProperties());
                        context.setBornHost(RemotingHelper.parseChannelRemoteAddr(ctx.channel()));
                        context.setBrokerAddr(this.brokerController.getBrokerAddr());
                        context.setQueueId(requestHeader.getQueueId());
                    }

                    hook.sendMessageBefore(context);
                    if (requestHeader != null) {
                        requestHeader.setProperties(context.getMsgProps());
                    }
                } catch (Throwable e) {
                    // Ignore
                }
            }
        }
    }

    // 反射创建指定类型的对象并根据request的extFields属性中的值为对象赋值
    protected SendMessageRequestHeader parseRequestHeader(RemotingCommand request)
        throws RemotingCommandException {

        SendMessageRequestHeaderV2 requestHeaderV2 = null;
        SendMessageRequestHeader requestHeader = null;
        // 如果没有break，则某个case满足后其后的所有case都会被执行
        switch (request.getCode()) {
            case RequestCode.SEND_BATCH_MESSAGE:
            case RequestCode.SEND_MESSAGE_V2:
                requestHeaderV2 =
                    (SendMessageRequestHeaderV2) request
                        .decodeCommandCustomHeader(SendMessageRequestHeaderV2.class);
            case RequestCode.SEND_MESSAGE:
                // 如果code是SEND_MESSAGE_V2则requestHeaderV2 != null
                if (null == requestHeaderV2) {
                    requestHeader =
                        (SendMessageRequestHeader) request
                            .decodeCommandCustomHeader(SendMessageRequestHeader.class);
                } else {
                    requestHeader = SendMessageRequestHeaderV2.createSendMessageRequestHeaderV1(requestHeaderV2);
                }
            default:
                break;
        }
        return requestHeader;
    }

    public void executeSendMessageHookAfter(final RemotingCommand response, final SendMessageContext context) {
        if (hasSendMessageHook()) {
            for (SendMessageHook hook : this.sendMessageHookList) {
                try {
                    if (response != null) {
                        final SendMessageResponseHeader responseHeader =
                            (SendMessageResponseHeader) response.readCustomHeader();
                        context.setMsgId(responseHeader.getMsgId());
                        context.setQueueId(responseHeader.getQueueId());
                        context.setQueueOffset(responseHeader.getQueueOffset());
                        context.setCode(response.getCode());
                        context.setErrorMsg(response.getRemark());
                    }
                    hook.sendMessageAfter(context);
                } catch (Throwable e) {
                    // Ignore
                }
            }
        }
    }

    @Override
    public boolean rejectRequest() {
        return false;
    }
}
