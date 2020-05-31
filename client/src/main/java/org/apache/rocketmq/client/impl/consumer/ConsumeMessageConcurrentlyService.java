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
package org.apache.rocketmq.client.impl.consumer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyContext;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.ConsumeReturnType;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.client.hook.ConsumeMessageContext;
import org.apache.rocketmq.client.log.ClientLogger;
import org.apache.rocketmq.client.stat.ConsumerStatsManager;
import org.apache.rocketmq.common.MixAll;
import org.apache.rocketmq.common.ThreadFactoryImpl;
import org.apache.rocketmq.common.message.MessageAccessor;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.common.message.MessageQueue;
import org.apache.rocketmq.common.protocol.body.CMResult;
import org.apache.rocketmq.common.protocol.body.ConsumeMessageDirectlyResult;
import org.apache.rocketmq.logging.InternalLogger;
import org.apache.rocketmq.remoting.common.RemotingHelper;

public class ConsumeMessageConcurrentlyService implements ConsumeMessageService {
    private static final InternalLogger log = ClientLogger.getLog();
    private final DefaultMQPushConsumerImpl defaultMQPushConsumerImpl;
    private final DefaultMQPushConsumer defaultMQPushConsumer;
    private final MessageListenerConcurrently messageListener;
    private final BlockingQueue<Runnable> consumeRequestQueue;
    private final ThreadPoolExecutor consumeExecutor;
    private final String consumerGroup;

    private final ScheduledExecutorService scheduledExecutorService;
    private final ScheduledExecutorService cleanExpireMsgExecutors;

    public ConsumeMessageConcurrentlyService(DefaultMQPushConsumerImpl defaultMQPushConsumerImpl,
        MessageListenerConcurrently messageListener) {
        this.defaultMQPushConsumerImpl = defaultMQPushConsumerImpl;
        // messageListener就是用户编写的消息消费逻辑
        this.messageListener = messageListener;

        // 通过defaultMQPushConsumer能够获取到配置
        this.defaultMQPushConsumer = this.defaultMQPushConsumerImpl.getDefaultMQPushConsumer();
        // 当前消费者的消费者组
        this.consumerGroup = this.defaultMQPushConsumer.getConsumerGroup();
        this.consumeRequestQueue = new LinkedBlockingQueue<Runnable>();

        // 初始化线程池
        this.consumeExecutor = new ThreadPoolExecutor(
            this.defaultMQPushConsumer.getConsumeThreadMin(), // 默认20
            this.defaultMQPushConsumer.getConsumeThreadMax(), // 默认20
            1000 * 60,
            TimeUnit.MILLISECONDS,
            this.consumeRequestQueue, // 使用无界同步队列
            new ThreadFactoryImpl("ConsumeMessageThread_"));

        // 初始化一个单线程的线程池，专门用于提交消费请求
        this.scheduledExecutorService = Executors.newSingleThreadScheduledExecutor(new ThreadFactoryImpl("ConsumeMessageScheduledThread_"));
        // 初始化一个单线程的线程池，专门用于检查消息消费时候超时，超时则发送给broker，broker会将消息保存到retry topic
        this.cleanExpireMsgExecutors = Executors.newSingleThreadScheduledExecutor(new ThreadFactoryImpl("CleanExpireMsgScheduledThread_"));
    }

    public void start() {
        // 定时执行cleanExpireMsg方法，cleanExpireMsg方法会检查消息是否消费超时，如果超时则会发送CONSUMER_SEND_MSG_BACK请求给broker，
        // 请求中带有消费超时的消息，broker会将消息保存到重试队列
        this.cleanExpireMsgExecutors.scheduleAtFixedRate(new Runnable() {

            @Override
            public void run() {
                cleanExpireMsg();
            }

        }, this.defaultMQPushConsumer.getConsumeTimeout(), this.defaultMQPushConsumer.getConsumeTimeout(), TimeUnit.MINUTES);
    }

    public void shutdown() {
        this.scheduledExecutorService.shutdown();
        this.consumeExecutor.shutdown();
        this.cleanExpireMsgExecutors.shutdown();
    }

    @Override
    public void updateCorePoolSize(int corePoolSize) {
        if (corePoolSize > 0
            && corePoolSize <= Short.MAX_VALUE
            && corePoolSize < this.defaultMQPushConsumer.getConsumeThreadMax()) {
            this.consumeExecutor.setCorePoolSize(corePoolSize);
        }
    }

    @Override
    public void incCorePoolSize() {
        // long corePoolSize = this.consumeExecutor.getCorePoolSize();
        // if (corePoolSize < this.defaultMQPushConsumer.getConsumeThreadMax())
        // {
        // this.consumeExecutor.setCorePoolSize(this.consumeExecutor.getCorePoolSize()
        // + 1);
        // }
        // log.info("incCorePoolSize Concurrently from {} to {}, ConsumerGroup:
        // {}",
        // corePoolSize,
        // this.consumeExecutor.getCorePoolSize(),
        // this.consumerGroup);
    }

    @Override
    public void decCorePoolSize() {
        // long corePoolSize = this.consumeExecutor.getCorePoolSize();
        // if (corePoolSize > this.defaultMQPushConsumer.getConsumeThreadMin())
        // {
        // this.consumeExecutor.setCorePoolSize(this.consumeExecutor.getCorePoolSize()
        // - 1);
        // }
        // log.info("decCorePoolSize Concurrently from {} to {}, ConsumerGroup:
        // {}",
        // corePoolSize,
        // this.consumeExecutor.getCorePoolSize(),
        // this.consumerGroup);
    }

    @Override
    public int getCorePoolSize() {
        return this.consumeExecutor.getCorePoolSize();
    }

    @Override
    // 直接消费指定消息，该方法不会影响消费者的消息位移，通常也不会被使用，下面的实现在ConsumeRequest类的run方法中也都分析过了
    public ConsumeMessageDirectlyResult consumeMessageDirectly(MessageExt msg, String brokerName) {
        ConsumeMessageDirectlyResult result = new ConsumeMessageDirectlyResult();
        result.setOrder(false);
        result.setAutoCommit(true);

        List<MessageExt> msgs = new ArrayList<MessageExt>();
        msgs.add(msg);
        MessageQueue mq = new MessageQueue();
        mq.setBrokerName(brokerName);
        mq.setTopic(msg.getTopic());
        mq.setQueueId(msg.getQueueId());

        ConsumeConcurrentlyContext context = new ConsumeConcurrentlyContext(mq);

        this.defaultMQPushConsumerImpl.resetRetryAndNamespace(msgs, this.consumerGroup);

        final long beginTime = System.currentTimeMillis();

        log.info("consumeMessageDirectly receive new message: {}", msg);

        try {
            ConsumeConcurrentlyStatus status = this.messageListener.consumeMessage(msgs, context);
            if (status != null) {
                switch (status) {
                    case CONSUME_SUCCESS:
                        result.setConsumeResult(CMResult.CR_SUCCESS);
                        break;
                    case RECONSUME_LATER:
                        result.setConsumeResult(CMResult.CR_LATER);
                        break;
                    default:
                        break;
                }
            } else {
                result.setConsumeResult(CMResult.CR_RETURN_NULL);
            }
        } catch (Throwable e) {
            result.setConsumeResult(CMResult.CR_THROW_EXCEPTION);
            result.setRemark(RemotingHelper.exceptionSimpleDesc(e));

            log.warn(String.format("consumeMessageDirectly exception: %s Group: %s Msgs: %s MQ: %s",
                RemotingHelper.exceptionSimpleDesc(e),
                ConsumeMessageConcurrentlyService.this.consumerGroup,
                msgs,
                mq), e);
        }

        result.setSpentTimeMills(System.currentTimeMillis() - beginTime);

        log.info("consumeMessageDirectly Result: {}", result);

        return result;
    }

    @Override
    public void submitConsumeRequest(
        final List<MessageExt> msgs,
        final ProcessQueue processQueue,
        final MessageQueue messageQueue,
        final boolean dispatchToConsume) {
        // 获取一次能够批量消费消息的数量，默认为1
        final int consumeBatchSize = this.defaultMQPushConsumer.getConsumeMessageBatchMaxSize();
        if (msgs.size() <= consumeBatchSize) {
            // 消息数量在限定值以下直接创建ConsumeRequest对象并提交给consumeExecutor线程池进行消费
            ConsumeRequest consumeRequest = new ConsumeRequest(msgs, processQueue, messageQueue);
            try {
                // ConsumeRequest类实现了Runnable接口，这里执行submit相当于执行ConsumeRequest类的run方法
                this.consumeExecutor.submit(consumeRequest);
            } catch (RejectedExecutionException e) {
                // 线程池慢了导致拒绝执行时过一段时间再执行
                this.submitConsumeRequestLater(consumeRequest);
            }
        } else {
            // 如果消息数量超过了一次能够批量消费的值，则分批消费
            for (int total = 0; total < msgs.size(); ) {
                List<MessageExt> msgThis = new ArrayList<MessageExt>(consumeBatchSize);
                // 组成批量消息
                for (int i = 0; i < consumeBatchSize; i++, total++) {
                    if (total < msgs.size()) {
                        msgThis.add(msgs.get(total));
                    } else {
                        break;
                    }
                }

                ConsumeRequest consumeRequest = new ConsumeRequest(msgThis, processQueue, messageQueue);
                try {
                    this.consumeExecutor.submit(consumeRequest);
                } catch (RejectedExecutionException e) {
                    for (; total < msgs.size(); total++) {
                        msgThis.add(msgs.get(total));
                    }

                    // 线程池慢了导致拒绝执行时过一段时间再执行
                    this.submitConsumeRequestLater(consumeRequest);
                }
            }
        }
    }


    private void cleanExpireMsg() {
        // 遍历当前消费者负责消费的队列
        Iterator<Map.Entry<MessageQueue, ProcessQueue>> it =
            this.defaultMQPushConsumerImpl.getRebalanceImpl().getProcessQueueTable().entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<MessageQueue, ProcessQueue> next = it.next();
            ProcessQueue pq = next.getValue();
            // cleanExpiredMsg方法会将消费时间超时的消息发送回broker，broker会将消息保存到retry topic
            pq.cleanExpiredMsg(this.defaultMQPushConsumer);
        }
    }

    public void processConsumeResult(
        final ConsumeConcurrentlyStatus status,
        final ConsumeConcurrentlyContext context,
        final ConsumeRequest consumeRequest
    ) {
        // 获取批量消息消费成功的位置，注意通常用户编写的消费逻辑不会修改这个值，而这个值的默认值为Integer.MAX_VALUE，即不发生异常的情
        // 况下默认认为所有消息消费成功，消费逻辑可以修改这个值以实现只确认消费批量消息的指定位置及该位置之前的消息
        int ackIndex = context.getAckIndex();

        if (consumeRequest.getMsgs().isEmpty())
            return;

        switch (status) {
            case CONSUME_SUCCESS: // 消费成功
                // ackIndex默认等于Integer.MAX_VALUE，所以这里需要修正该值
                if (ackIndex >= consumeRequest.getMsgs().size()) {
                    ackIndex = consumeRequest.getMsgs().size() - 1;
                }
                // 消费成功的消息个数
                int ok = ackIndex + 1;
                // 成功的数量不等于批量消息的数量则认为消费失败
                int failed = consumeRequest.getMsgs().size() - ok;
                // 统计消费结果
                this.getConsumerStatsManager().incConsumeOKTPS(consumerGroup, consumeRequest.getMessageQueue().getTopic(), ok);
                this.getConsumerStatsManager().incConsumeFailedTPS(consumerGroup, consumeRequest.getMessageQueue().getTopic(), failed);
                break;
            case RECONSUME_LATER:
                ackIndex = -1;
                this.getConsumerStatsManager().incConsumeFailedTPS(consumerGroup, consumeRequest.getMessageQueue().getTopic(),
                    consumeRequest.getMsgs().size());
                break;
            default:
                break;
        }

        switch (this.defaultMQPushConsumer.getMessageModel()) {
            case BROADCASTING:
                // 广播模式消费失败的消息只是记录日志，不做补偿操作
                for (int i = ackIndex + 1; i < consumeRequest.getMsgs().size(); i++) {
                    MessageExt msg = consumeRequest.getMsgs().get(i);
                    log.warn("BROADCASTING, the message consume failed, drop it, {}", msg.toString());
                }
                break;
            case CLUSTERING:
                // 集群模式需要完成消息重试逻辑
                List<MessageExt> msgBackFailed = new ArrayList<MessageExt>(consumeRequest.getMsgs().size());
                // 遍历ackIndex + 1及之后的消息，即消费失败的消息
                for (int i = ackIndex + 1; i < consumeRequest.getMsgs().size(); i++) {
                    MessageExt msg = consumeRequest.getMsgs().get(i);
                    // 发送消息给broker，broker会将消息保存到retry topic
                    boolean result = this.sendMessageBack(msg, context);
                    if (!result) {
                        // 发送失败增加重试次数，即保存到msgBackFailed记录下来
                        msg.setReconsumeTimes(msg.getReconsumeTimes() + 1);
                        msgBackFailed.add(msg);
                    }
                }

                if (!msgBackFailed.isEmpty()) {
                    // 从批量消息集合中移除发送失败的消息，剩下的消息是消费成功和发送给broker成功的消息
                    consumeRequest.getMsgs().removeAll(msgBackFailed);

                    // 发送给broker失败的消息一段时间后直接在消费者端重新尝试消费
                    this.submitConsumeRequestLater(msgBackFailed, consumeRequest.getProcessQueue(), consumeRequest.getMessageQueue());
                }
                break;
            default:
                break;
        }

        // 从ProcessQueue中移除消费成功和消费失败后发送回broker成功的消息，如果移除消息完成后，ProcessQueue中还保留着消息，则
        // removeMessage方法的返回值为移除之后ProcessQueue保存的消息中位移的最小值，如果移除之前ProcessQueue保存的消息为空，则返回
        // 值为-1（通常不会出现这种情况，因为ProcessQueue中至少会包含下面传入的这些消息），如果移除之后ProcessQueue保存的消息为空，
        // 则返回ProcessQueue保存过的消息中位移的最大值，通过removeMessage的这种实现机制，再加上下面的updateOffset方法，就实现了消息
        // 消费后的自动提交
        long offset = consumeRequest.getProcessQueue().removeMessage(consumeRequest.getMsgs());
        if (offset >= 0 && !consumeRequest.getProcessQueue().isDropped()) {
            // 更新offsetStore中指定队列的消息位移，这里updateOffset方法的increaseOnly为true，表示offsetStore中指定队列的消息位移
            // 如果小于传入的offset的话才更新，否则不更新，这种机制就是消息位移自动提交的关键
            this.defaultMQPushConsumerImpl.getOffsetStore().updateOffset(consumeRequest.getMessageQueue(), offset, true);
        }
    }

    public ConsumerStatsManager getConsumerStatsManager() {
        return this.defaultMQPushConsumerImpl.getConsumerStatsManager();
    }

    public boolean sendMessageBack(final MessageExt msg, final ConsumeConcurrentlyContext context) {
        int delayLevel = context.getDelayLevelWhenNextConsume();

        // Wrap topic with namespace before sending back message.
        msg.setTopic(this.defaultMQPushConsumer.withNamespace(msg.getTopic()));
        try {
            this.defaultMQPushConsumerImpl.sendMessageBack(msg, delayLevel, context.getMessageQueue().getBrokerName());
            return true;
        } catch (Exception e) {
            log.error("sendMessageBack exception, group: " + this.consumerGroup + " msg: " + msg.toString(), e);
        }

        return false;
    }

    private void submitConsumeRequestLater(
        final List<MessageExt> msgs,
        final ProcessQueue processQueue,
        final MessageQueue messageQueue
    ) {

        this.scheduledExecutorService.schedule(new Runnable() {

            @Override
            public void run() {
                ConsumeMessageConcurrentlyService.this.submitConsumeRequest(msgs, processQueue, messageQueue, true);
            }
        }, 5000, TimeUnit.MILLISECONDS);
    }

    private void submitConsumeRequestLater(final ConsumeRequest consumeRequest
    ) {
        // ConsumeRequest提交给线程池执行时如果被拒绝了，则5秒后再次提交
        this.scheduledExecutorService.schedule(new Runnable() {

            @Override
            public void run() {
                ConsumeMessageConcurrentlyService.this.consumeExecutor.submit(consumeRequest);
            }
        }, 5000, TimeUnit.MILLISECONDS);
    }

    class ConsumeRequest implements Runnable {
        private final List<MessageExt> msgs;
        private final ProcessQueue processQueue;
        private final MessageQueue messageQueue;

        public ConsumeRequest(List<MessageExt> msgs, ProcessQueue processQueue, MessageQueue messageQueue) {
            this.msgs = msgs;
            this.processQueue = processQueue;
            this.messageQueue = messageQueue;
        }

        public List<MessageExt> getMsgs() {
            return msgs;
        }

        public ProcessQueue getProcessQueue() {
            return processQueue;
        }

        @Override
        public void run() {
            // 如果processQueue已经不由当前消费者消费，直接返回
            if (this.processQueue.isDropped()) {
                log.info("the message queue not be able to consume, because it's dropped. group={} {}", ConsumeMessageConcurrentlyService.this.consumerGroup, this.messageQueue);
                return;
            }

            // MessageListenerConcurrently就是用户实现的消息消费逻辑
            MessageListenerConcurrently listener = ConsumeMessageConcurrentlyService.this.messageListener;
            // 消费逻辑通过ConsumeConcurrentlyContext对象能够改变确认消费成功的消息位置，实现方式在processConsumeResult方法能看到
            ConsumeConcurrentlyContext context = new ConsumeConcurrentlyContext(messageQueue);
            // ConsumeConcurrentlyStatus为消费结果，有CONSUME_SUCCESS和RECONSUME_LATER
            ConsumeConcurrentlyStatus status = null;
            // 如果消息的topic为retry topic，则恢复消息的topic为原始的topic，如果topic存在namespace，则移除namespace
            defaultMQPushConsumerImpl.resetRetryAndNamespace(msgs, defaultMQPushConsumer.getConsumerGroup());

            ConsumeMessageContext consumeMessageContext = null;
            // 存在hook则调用hook的before方法
            if (ConsumeMessageConcurrentlyService.this.defaultMQPushConsumerImpl.hasHook()) {
                consumeMessageContext = new ConsumeMessageContext();
                consumeMessageContext.setNamespace(defaultMQPushConsumer.getNamespace());
                consumeMessageContext.setConsumerGroup(defaultMQPushConsumer.getConsumerGroup());
                consumeMessageContext.setProps(new HashMap<String, String>());
                consumeMessageContext.setMq(messageQueue);
                consumeMessageContext.setMsgList(msgs);
                consumeMessageContext.setSuccess(false);
                ConsumeMessageConcurrentlyService.this.defaultMQPushConsumerImpl.executeHookBefore(consumeMessageContext);
            }

            long beginTimestamp = System.currentTimeMillis();
            boolean hasException = false;
            ConsumeReturnType returnType = ConsumeReturnType.SUCCESS;
            try {
                if (msgs != null && !msgs.isEmpty()) {
                    for (MessageExt msg : msgs) {
                        // 设置消息被消费的时间为当前时间
                        MessageAccessor.setConsumeStartTimeStamp(msg, String.valueOf(System.currentTimeMillis()));
                    }
                }
                // 同步调用消费逻辑
                status = listener.consumeMessage(Collections.unmodifiableList(msgs), context);
            } catch (Throwable e) {
                log.warn("consumeMessage exception: {} Group: {} Msgs: {} MQ: {}",
                    RemotingHelper.exceptionSimpleDesc(e),
                    ConsumeMessageConcurrentlyService.this.consumerGroup,
                    msgs,
                    messageQueue);
                hasException = true;
            }
            long consumeRT = System.currentTimeMillis() - beginTimestamp;
            if (null == status) {
                if (hasException) {
                    returnType = ConsumeReturnType.EXCEPTION;
                } else {
                    returnType = ConsumeReturnType.RETURNNULL;
                }
            } else if (consumeRT >= defaultMQPushConsumer.getConsumeTimeout() * 60 * 1000) {
                returnType = ConsumeReturnType.TIME_OUT; // 消费超时
            } else if (ConsumeConcurrentlyStatus.RECONSUME_LATER == status) {
                returnType = ConsumeReturnType.FAILED; // 消费失败
            } else if (ConsumeConcurrentlyStatus.CONSUME_SUCCESS == status) {
                returnType = ConsumeReturnType.SUCCESS; // 消费成功
            }

            // 将消费结果保存到consumeMessageContext，之后回调用hook的after方法
            if (ConsumeMessageConcurrentlyService.this.defaultMQPushConsumerImpl.hasHook()) {
                consumeMessageContext.getProps().put(MixAll.CONSUME_CONTEXT_TYPE, returnType.name());
            }

            if (null == status) {
                log.warn("consumeMessage return null, Group: {} Msgs: {} MQ: {}",
                    ConsumeMessageConcurrentlyService.this.consumerGroup,
                    msgs,
                    messageQueue);
                // 消费结果为null只能当作RECONSUME_LATER处理，即过一段时间再尝试消费
                status = ConsumeConcurrentlyStatus.RECONSUME_LATER;
            }

            // 消费完成后调用hook的after
            if (ConsumeMessageConcurrentlyService.this.defaultMQPushConsumerImpl.hasHook()) {
                consumeMessageContext.setStatus(status.toString());
                consumeMessageContext.setSuccess(ConsumeConcurrentlyStatus.CONSUME_SUCCESS == status);
                ConsumeMessageConcurrentlyService.this.defaultMQPushConsumerImpl.executeHookAfter(consumeMessageContext);
            }

            // 统计消费时间
            ConsumeMessageConcurrentlyService.this.getConsumerStatsManager()
                .incConsumeRT(ConsumeMessageConcurrentlyService.this.consumerGroup, messageQueue.getTopic(), consumeRT);

            if (!processQueue.isDropped()) {
                // 处理消费结果
                ConsumeMessageConcurrentlyService.this.processConsumeResult(status, context, this);
            } else {
                log.warn("processQueue is dropped without process consume result. messageQueue={}, msgs={}", messageQueue, msgs);
            }
        }

        public MessageQueue getMessageQueue() {
            return messageQueue;
        }

    }
}
