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
package org.apache.rocketmq.broker.transaction.queue;

import org.apache.rocketmq.broker.transaction.AbstractTransactionalMessageCheckListener;
import org.apache.rocketmq.broker.transaction.OperationResult;
import org.apache.rocketmq.broker.transaction.TransactionalMessageService;
import org.apache.rocketmq.client.consumer.PullResult;
import org.apache.rocketmq.client.consumer.PullStatus;
import org.apache.rocketmq.common.MixAll;
import org.apache.rocketmq.common.constant.LoggerName;
import org.apache.rocketmq.common.message.MessageConst;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.common.message.MessageQueue;
import org.apache.rocketmq.common.protocol.ResponseCode;
import org.apache.rocketmq.common.protocol.header.EndTransactionRequestHeader;
import org.apache.rocketmq.logging.InternalLogger;
import org.apache.rocketmq.logging.InternalLoggerFactory;
import org.apache.rocketmq.store.MessageExtBrokerInner;
import org.apache.rocketmq.store.PutMessageResult;
import org.apache.rocketmq.store.PutMessageStatus;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class TransactionalMessageServiceImpl implements TransactionalMessageService {
    private static final InternalLogger log = InternalLoggerFactory.getLogger(LoggerName.TRANSACTION_LOGGER_NAME);

    private TransactionalMessageBridge transactionalMessageBridge;

    private static final int PULL_MSG_RETRY_NUMBER = 1;

    private static final int MAX_PROCESS_TIME_LIMIT = 60000;

    private static final int MAX_RETRY_COUNT_WHEN_HALF_NULL = 1;

    public TransactionalMessageServiceImpl(TransactionalMessageBridge transactionBridge) {
        this.transactionalMessageBridge = transactionBridge;
    }

    private ConcurrentHashMap<MessageQueue, MessageQueue> opQueueMap = new ConcurrentHashMap<>();

    @Override
    public PutMessageResult prepareMessage(MessageExtBrokerInner messageInner) {
        return transactionalMessageBridge.putHalfMessage(messageInner);
    }

    private boolean needDiscard(MessageExt msgExt, int transactionCheckMax) {
        String checkTimes = msgExt.getProperty(MessageConst.PROPERTY_TRANSACTION_CHECK_TIMES);
        int checkTime = 1;
        if (null != checkTimes) {
            checkTime = getInt(checkTimes);
            if (checkTime >= transactionCheckMax) {
                return true;
            } else {
                checkTime++;
            }
        }
        msgExt.putUserProperty(MessageConst.PROPERTY_TRANSACTION_CHECK_TIMES, String.valueOf(checkTime));
        return false;
    }

    private boolean needSkip(MessageExt msgExt) {
        long valueOfCurrentMinusBorn = System.currentTimeMillis() - msgExt.getBornTimestamp();
        if (valueOfCurrentMinusBorn
            > transactionalMessageBridge.getBrokerController().getMessageStoreConfig().getFileReservedTime()
            * 3600L * 1000) {
            log.info("Half message exceed file reserved time ,so skip it.messageId {},bornTime {}",
                msgExt.getMsgId(), msgExt.getBornTimestamp());
            return true;
        }
        return false;
    }

    private boolean putBackHalfMsgQueue(MessageExt msgExt, long offset) {
        // 根据msgExt创建一个新的消息对象并保存到DefaultMessageStore
        PutMessageResult putMessageResult = putBackToHalfQueueReturnResult(msgExt);
        if (putMessageResult != null
            && putMessageResult.getPutMessageStatus() == PutMessageStatus.PUT_OK) { // 保存成功
            // 重置消息的位移为新保存的位置的值
            msgExt.setQueueOffset(
                putMessageResult.getAppendMessageResult().getLogicsOffset());
            msgExt.setCommitLogOffset(
                putMessageResult.getAppendMessageResult().getWroteOffset());
            msgExt.setMsgId(putMessageResult.getAppendMessageResult().getMsgId());
            log.debug(
                "Send check message, the offset={} restored in queueOffset={} "
                    + "commitLogOffset={} "
                    + "newMsgId={} realMsgId={} topic={}",
                offset, msgExt.getQueueOffset(), msgExt.getCommitLogOffset(), msgExt.getMsgId(),
                msgExt.getUserProperty(MessageConst.PROPERTY_UNIQ_CLIENT_MESSAGE_ID_KEYIDX),
                msgExt.getTopic());
            return true;
        } else {
            log.error(
                "PutBackToHalfQueueReturnResult write failed, topic: {}, queueId: {}, "
                    + "msgId: {}",
                msgExt.getTopic(), msgExt.getQueueId(), msgExt.getMsgId());
            return false;
        }
    }

    @Override
    public void check(long transactionTimeout, int transactionCheckMax,
        AbstractTransactionalMessageCheckListener listener) {
        try {
            String topic = MixAll.RMQ_SYS_TRANS_HALF_TOPIC;
            // 获取RMQ_SYS_TRANS_HALF_TOPIC这个topic下的所有队列
            Set<MessageQueue> msgQueues = transactionalMessageBridge.fetchMessageQueues(topic);
            if (msgQueues == null || msgQueues.size() == 0) {
                log.warn("The queue of topic is empty :" + topic);
                return;
            }
            log.debug("Check topic={}, queues={}", topic, msgQueues);
            // 遍历RMQ_SYS_TRANS_HALF_TOPIC这个topic下的所有队列
            for (MessageQueue messageQueue : msgQueues) {
                long startTime = System.currentTimeMillis();
                // 创建这个队列对应的op队列
                MessageQueue opQueue = getOpQueue(messageQueue);
                // 获取当前队列已经被消费的消息的最大消费位移（相当于在队列中的数组位置）
                long halfOffset = transactionalMessageBridge.fetchConsumeOffset(messageQueue);
                // 获取op队列已经被消费的消息的最大消费位移
                long opOffset = transactionalMessageBridge.fetchConsumeOffset(opQueue);
                log.info("Before check, the queue={} msgOffset={} opOffset={}", messageQueue, halfOffset, opOffset);
                if (halfOffset < 0 || opOffset < 0) {
                    log.error("MessageQueue: {} illegal offset read: {}, op offset: {},skip this queue", messageQueue,
                        halfOffset, opOffset);
                    continue;
                }

                List<Long> doneOpOffset = new ArrayList<>();
                HashMap<Long, Long> removeMap = new HashMap<>();
                // fillOpRemoveMap方法会从opQueue的opOffset位置开始获取消息，并将已经check过的事务消息的op消息在队列中的数组位置保存到doneOpOffset，
                // 还未check但是已经有了op消息的事务消息放到removeMap，map的key是事务消息在其队列中的数组位置，value为op消息在其队列中的数组位置
                // 通过数组位置查询consumequeue就能找到对应的消息索引，从而在commitlog文件中找到消息
                PullResult pullResult = fillOpRemoveMap(removeMap, opQueue, opOffset, halfOffset, doneOpOffset);
                if (null == pullResult) {
                    log.error("The queue={} check msgOffset={} with opOffset={} failed, pullResult is null",
                        messageQueue, halfOffset, opOffset);
                    continue;
                }
                // single thread
                // 查询事务消息失败后重试的次数
                int getMessageNullCount = 1;
                long newOffset = halfOffset;
                long i = halfOffset;
                while (true) {
                    // 超过最大处理时间不再处理
                    if (System.currentTimeMillis() - startTime > MAX_PROCESS_TIME_LIMIT) {
                        log.info("Queue={} process time reach max={}", messageQueue, MAX_PROCESS_TIME_LIMIT);
                        break;
                    }
                    // i的初始值是事务消息被消费的进度（数组位置），如果removeMap包含i，说明当前事务消息已经存在对应的op消息，不需要再check
                    // 了，直接添加到doneOpOffset中
                    if (removeMap.containsKey(i)) {
                        log.info("Half offset {} has been committed/rolled back", i);
                        Long removedOpOffset = removeMap.remove(i);
                        doneOpOffset.add(removedOpOffset);
                    } else {
                        // 获取messageQueue这个队列的第i条消息，原理是找到messageQueue对应的consumequeue文件，并获取该文件的
                        // 第i个消息索引，再根据消息索引返回消息
                        GetResult getResult = getHalfMsg(messageQueue, i);
                        MessageExt msgExt = getResult.getMsg();
                        if (msgExt == null) {
                            if (getMessageNullCount++ > MAX_RETRY_COUNT_WHEN_HALF_NULL) { // 获取失败并超过最大重试次数时退出循环
                                break;
                            }
                            if (getResult.getPullResult().getPullStatus() == PullStatus.NO_NEW_MSG) { // 没有可以消费的消息时退出循环
                                log.debug("No new msg, the miss offset={} in={}, continue check={}, pull result={}", i,
                                    messageQueue, getMessageNullCount, getResult.getPullResult());
                                break;
                            } else {
                                log.info("Illegal offset, the miss offset={} in={}, continue check={}, pull result={}",
                                    i, messageQueue, getMessageNullCount, getResult.getPullResult());
                                i = getResult.getPullResult().getNextBeginOffset();
                                newOffset = i;
                                continue;
                            }
                        }

                        // 判断是否超过了check的次数，默认15次，或者判断是否op消息存在的时间超过了设定的保留时间，默认72小时
                        if (needDiscard(msgExt, transactionCheckMax) || needSkip(msgExt)) {
                            // 创建一个topic为TRANS_CHECK_MAX_TIME_TOPIC的消息并保存到DefaultMessageStore
                            listener.resolveDiscardMsg(msgExt);
                            // 更新需要处理的消息位移
                            newOffset = i + 1;
                            i++;
                            // 继续处理下一条消息，当前消息被忽略了，也不会再被check
                            continue;
                        }
                        if (msgExt.getStoreTimestamp() >= startTime) {
                            log.debug("Fresh stored. the miss offset={}, check it later, store={}", i,
                                new Date(msgExt.getStoreTimestamp()));
                            break;
                        }

                        // 计算事务消息存在的时间
                        long valueOfCurrentMinusBorn = System.currentTimeMillis() - msgExt.getBornTimestamp();
                        // transactionTimeout表示事务的过期时间，也就是什么时间后才开始进行事务回查，在transactionTimeout
                        // 时间内，不执行事务回查，给本地事务留下了执行时间
                        long checkImmunityTime = transactionTimeout;
                        // CHECK_IMMUNITY_TIME_IN_SECONDS属性为用户属性，可以覆盖transactionTimeout的值
                        String checkImmunityTimeStr = msgExt.getUserProperty(MessageConst.PROPERTY_CHECK_IMMUNITY_TIME_IN_SECONDS);
                        if (null != checkImmunityTimeStr) {
                            checkImmunityTime = getImmunityTime(checkImmunityTimeStr, transactionTimeout);
                            // 如果还没到需要回查的时间，为了推进check的进度，将当前消息重新保存一次到DefaultMessageStore，并且
                            // 增加newOffset的值，相当于忽略当前事务消息，该事务消息的副本已经被重新保存到最新的位置了
                            if (valueOfCurrentMinusBorn < checkImmunityTime) {
                                if (checkPrepareQueueOffset(removeMap, doneOpOffset, msgExt)) {
                                    newOffset = i + 1;
                                    i++;
                                    continue;
                                }
                            }
                        } else {
                            // 如果当前事务消息的创建时间比当前check方法执行时间要晚，则直接break，下次再check
                            if ((0 <= valueOfCurrentMinusBorn) && (valueOfCurrentMinusBorn < checkImmunityTime)) {
                                log.debug("New arrived, the miss offset={}, check it later checkImmunity={}, born={}", i,
                                    checkImmunityTime, new Date(msgExt.getBornTimestamp()));
                                break;
                            }
                        }
                        List<MessageExt> opMsg = pullResult.getMsgFoundList();
                        // 如果op消息列表为空并且到了check的时间，或者获取到的op消息的最大创建时间减去开始时间超过了事务超时时间，则需要发送
                        // 事务回查，什么时候会出现(opMsg != null && (opMsg.get(opMsg.size() - 1).getBornTimestamp() - startTime > transactionTimeout))
                        // 呢，看下面isNeedCheck为true时的处理可以发现，每次回查消息前，都会将事务消息重新保存到DefaultMessageStore，
                        // 之后会异步发送事务消息回查请求，既然是异步发送，如何知道回查结果呢，答案是不需要知道结果，当生产者收到消息回查请求后，
                        // 会检查本地事务并再次发送提交或回滚事务消息的请求，可能发送成功，也可能发送失败，也可能还不知道事务状态，如果是
                        // 成功发送提交或回滚事务的请求，则会有对应的op消息被保存，也这是fillOpRemoveMap方法实现的原因，该方法需要根据op
                        // 消息对当前正在check的事务消息去重，避免重复回查事务状态；另外如果生产者没有返回本地事务的状态，或者还不知道本地
                        // 事务的状态也没关系，下面在回查之前会调用putBackHalfMsgQueue方法重新保存一个事务消息到DefaultMessageStore，
                        // 所以当前的事务消息在发送回查请求后会被认为已经处理，在之后这个事务消息还有机会再次被check。
                        boolean isNeedCheck = (opMsg == null && valueOfCurrentMinusBorn > checkImmunityTime)
                            || (opMsg != null && (opMsg.get(opMsg.size() - 1).getBornTimestamp() - startTime > transactionTimeout))
                            || (valueOfCurrentMinusBorn <= -1);

                        // 如果需要回查
                        if (isNeedCheck) {
                            // 根据msgExt创建一个新的消息对象并保存到DefaultMessageStore，所以每次回查前消息都会被重新保存一次到DefaultMessageStore
                            if (!putBackHalfMsgQueue(msgExt, i)) {
                                continue;
                            }
                            // 异步向生产者发送回查事务状态请求
                            listener.resolveHalfMsg(msgExt);
                        } else {
                            // 不需要回查的消息，则再获取一批op消息，重新开始循环，出现这个情况可能是查询op消息返回空，此时重新查询一次op消息，
                            // 也可能是op消息不为空，但是不满足(opMsg.get(opMsg.size() - 1).getBornTimestamp() - startTime > transactionTimeout)
                            // 即查到的op消息的创建时间和循环开始时间的差都小于事务超时时间，op消息创建的时间肯定是大于其对应的事务消息的创建
                            // 时间的，所以这就相当于这些op消息的事务消息都还没有到达事务超时时间，什么时候会这样呢，这是因为fillOpRemoveMap
                            // 方法一次最多只查询32条op消息，所以当前事务消息对应的op消息很可能还在后面，所以需要再调用fillOpRemoveMap方法，并
                            // 从pullResult.getNextBeginOffset()位置之后开始查
                            pullResult = fillOpRemoveMap(removeMap, opQueue, pullResult.getNextBeginOffset(), halfOffset, doneOpOffset);
                            log.debug("The miss offset:{} in messageQueue:{} need to get more opMsg, result is:{}", i,
                                messageQueue, pullResult);
                            continue;
                        }
                    }
                    // 更新下一次需要处理的消息位移
                    newOffset = i + 1;
                    i++;
                }
                // 如果有消息被成功处理了，提交事务消息队列的消费位移
                if (newOffset != halfOffset) {
                    transactionalMessageBridge.updateConsumeOffset(messageQueue, newOffset);
                }
                // 返回连续的doneOffset元素的最大值 + 1，关键要是连续的
                long newOpOffset = calculateOpOffset(doneOpOffset, opOffset);
                if (newOpOffset != opOffset) {
                    // 提交op队列的消费位移
                    transactionalMessageBridge.updateConsumeOffset(opQueue, newOpOffset);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            log.error("Check error", e);
        }

    }

    private long getImmunityTime(String checkImmunityTimeStr, long transactionTimeout) {
        long checkImmunityTime;

        checkImmunityTime = getLong(checkImmunityTimeStr);
        if (-1 == checkImmunityTime) {
            checkImmunityTime = transactionTimeout;
        } else {
            checkImmunityTime *= 1000;
        }
        return checkImmunityTime;
    }

    /**
     * Read op message, parse op message, and fill removeMap
     *
     * @param removeMap Half message to be remove, key:halfOffset, value: opOffset.
     * @param opQueue Op message queue.
     * @param pullOffsetOfOp The begin offset of op message queue.
     * @param miniOffset The current minimum offset of half message queue.
     * @param doneOpOffset Stored op messages that have been processed.
     * @return Op message result.
     */
    private PullResult fillOpRemoveMap(HashMap<Long, Long> removeMap,
        MessageQueue opQueue, long pullOffsetOfOp, long miniOffset, List<Long> doneOpOffset) {
        // 从op队列的pullOffsetOfOp位置开始获取最多32条消息进行消费
        PullResult pullResult = pullOpMsg(opQueue, pullOffsetOfOp, 32);
        if (null == pullResult) {
            return null;
        }
        if (pullResult.getPullStatus() == PullStatus.OFFSET_ILLEGAL
            || pullResult.getPullStatus() == PullStatus.NO_MATCHED_MSG) { // 如果消费的位移不合法，则更新消费的位移
            log.warn("The miss op offset={} in queue={} is illegal, pullResult={}", pullOffsetOfOp, opQueue,
                pullResult);
            transactionalMessageBridge.updateConsumeOffset(opQueue, pullResult.getNextBeginOffset());
            return pullResult;
        } else if (pullResult.getPullStatus() == PullStatus.NO_NEW_MSG) { // 如果没有可以消费的消息
            log.warn("The miss op offset={} in queue={} is NO_NEW_MSG, pullResult={}", pullOffsetOfOp, opQueue,
                pullResult);
            return pullResult;
        }
        List<MessageExt> opMsg = pullResult.getMsgFoundList();
        if (opMsg == null) {
            log.warn("The miss op offset={} in queue={} is empty, pullResult={}", pullOffsetOfOp, opQueue, pullResult);
            return pullResult;
        }
        // 遍历从op队列获取到的等待消费的消息
        for (MessageExt opMessageExt : opMsg) {
            // 解码op消息内容，op消息的内容是其对应的事务消息在队列中的位置（数组位置），根据这个位置能够从consumequeue文件查询消息
            Long queueOffset = getLong(new String(opMessageExt.getBody(), TransactionalMessageUtil.charset));
            log.debug("Topic: {} tags: {}, OpOffset: {}, HalfOffset: {}", opMessageExt.getTopic(),
                opMessageExt.getTags(), opMessageExt.getQueueOffset(), queueOffset);
            // op消息的tag都是TransactionalMessageUtil.REMOVETAG
            if (TransactionalMessageUtil.REMOVETAG.equals(opMessageExt.getTags())) {
                if (queueOffset < miniOffset) { // 小于miniOffset说明该op消息对应的事务消息已经check过了
                    // 保存op消息在其队列中的数组位置到doneOpOffset
                    doneOpOffset.add(opMessageExt.getQueueOffset());
                } else {
                    // queueOffset >= miniOffset时，说明当前op消息对应的事务消息还没有被check，但是已经有了op消息，说明事务状态
                    // 已经确定了，此时也没必要再check了，这里将这种消息保存到removeMap中，map的key为事务消息在队列中的数组位置，value为
                    // op消息在队列中的数组位置
                    removeMap.put(queueOffset, opMessageExt.getQueueOffset());
                }
            } else {
                log.error("Found a illegal tag in opMessageExt= {} ", opMessageExt);
            }
        }
        log.debug("Remove map: {}", removeMap);
        log.debug("Done op list: {}", doneOpOffset);
        return pullResult;
    }

    /**
     * If return true, skip this msg
     *
     * @param removeMap Op message map to determine whether a half message was responded by producer.
     * @param doneOpOffset Op Message which has been checked.
     * @param msgExt Half message
     * @return Return true if put success, otherwise return false.
     */
    private boolean checkPrepareQueueOffset(HashMap<Long, Long> removeMap, List<Long> doneOpOffset,
        MessageExt msgExt) {
        String prepareQueueOffsetStr = msgExt.getUserProperty(MessageConst.PROPERTY_TRANSACTION_PREPARED_QUEUE_OFFSET);
        if (null == prepareQueueOffsetStr) {
            // 根据msgExt创建一个消息，并设置该消息的TRAN_PREPARED_QUEUE_OFFSET属性，属性值为当前事务消息在队列中的位移
            // 创建之后将该消息保存到DefaultMessageStore
            return putImmunityMsgBackToHalfQueue(msgExt);
        } else {
            // 如果消息存在TRAN_PREPARED_QUEUE_OFFSET属性，说明之前已经check过了
            long prepareQueueOffset = getLong(prepareQueueOffsetStr);
            if (-1 == prepareQueueOffset) {
                return false;
            } else {
                // 如果事务消息已经存在对应的op消息，添加到doneOpOffset中
                if (removeMap.containsKey(prepareQueueOffset)) {
                    long tmpOpOffset = removeMap.remove(prepareQueueOffset);
                    doneOpOffset.add(tmpOpOffset);
                    return true;
                } else {
                    return putImmunityMsgBackToHalfQueue(msgExt);
                }
            }
        }
    }

    /**
     * Write messageExt to Half topic again
     *
     * @param messageExt Message will be write back to queue
     * @return Put result can used to determine the specific results of storage.
     */
    private PutMessageResult putBackToHalfQueueReturnResult(MessageExt messageExt) {
        PutMessageResult putMessageResult = null;
        try {
            MessageExtBrokerInner msgInner = transactionalMessageBridge.renewHalfMessageInner(messageExt);
            putMessageResult = transactionalMessageBridge.putMessageReturnResult(msgInner);
        } catch (Exception e) {
            log.warn("PutBackToHalfQueueReturnResult error", e);
        }
        return putMessageResult;
    }

    private boolean putImmunityMsgBackToHalfQueue(MessageExt messageExt) {
        MessageExtBrokerInner msgInner = transactionalMessageBridge.renewImmunityHalfMessageInner(messageExt);
        return transactionalMessageBridge.putMessage(msgInner);
    }

    /**
     * Read half message from Half Topic
     *
     * @param mq Target message queue, in this method, it means the half message queue.
     * @param offset Offset in the message queue.
     * @param nums Pull message number.
     * @return Messages pulled from half message queue.
     */
    private PullResult pullHalfMsg(MessageQueue mq, long offset, int nums) {
        return transactionalMessageBridge.getHalfMessage(mq.getQueueId(), offset, nums);
    }

    /**
     * Read op message from Op Topic
     *
     * @param mq Target Message Queue
     * @param offset Offset in the message queue
     * @param nums Pull message number
     * @return Messages pulled from operate message queue.
     */
    private PullResult pullOpMsg(MessageQueue mq, long offset, int nums) {
        return transactionalMessageBridge.getOpMessage(mq.getQueueId(), offset, nums);
    }

    private Long getLong(String s) {
        long v = -1;
        try {
            v = Long.valueOf(s);
        } catch (Exception e) {
            log.error("GetLong error", e);
        }
        return v;

    }

    private Integer getInt(String s) {
        int v = -1;
        try {
            v = Integer.valueOf(s);
        } catch (Exception e) {
            log.error("GetInt error", e);
        }
        return v;

    }

    private long calculateOpOffset(List<Long> doneOffset, long oldOffset) {
        Collections.sort(doneOffset);
        long newOffset = oldOffset;
        // 获取连续的doneOffset元素的最大值 + 1
        for (int i = 0; i < doneOffset.size(); i++) {
            if (doneOffset.get(i) == newOffset) {
                newOffset++;
            } else {
                break;
            }
        }
        return newOffset;

    }

    private MessageQueue getOpQueue(MessageQueue messageQueue) {
        MessageQueue opQueue = opQueueMap.get(messageQueue);
        if (opQueue == null) {
            opQueue = new MessageQueue(TransactionalMessageUtil.buildOpTopic(), messageQueue.getBrokerName(),
                messageQueue.getQueueId());
            opQueueMap.put(messageQueue, opQueue);
        }
        return opQueue;

    }

    private GetResult getHalfMsg(MessageQueue messageQueue, long offset) {
        GetResult getResult = new GetResult();

        // 从commitlog中获取消息
        PullResult result = pullHalfMsg(messageQueue, offset, PULL_MSG_RETRY_NUMBER);
        getResult.setPullResult(result);
        List<MessageExt> messageExts = result.getMsgFoundList();
        if (messageExts == null) {
            return getResult;
        }
        getResult.setMsg(messageExts.get(0));
        return getResult;
    }

    private OperationResult getHalfMessageByOffset(long commitLogOffset) {
        OperationResult response = new OperationResult();
        // 根据消息位移查询消息
        MessageExt messageExt = this.transactionalMessageBridge.lookMessageByOffset(commitLogOffset);
        if (messageExt != null) {
            // 查询成功
            response.setPrepareMessage(messageExt);
            response.setResponseCode(ResponseCode.SUCCESS);
        } else {
            // 查询失败
            response.setResponseCode(ResponseCode.SYSTEM_ERROR);
            response.setResponseRemark("Find prepared transaction message failed");
        }
        return response;
    }

    @Override
    public boolean deletePrepareMessage(MessageExt msgExt) {
        if (this.transactionalMessageBridge.putOpMessage(msgExt, TransactionalMessageUtil.REMOVETAG)) {
            log.debug("Transaction op message write successfully. messageId={}, queueId={} msgExt:{}", msgExt.getMsgId(), msgExt.getQueueId(), msgExt);
            return true;
        } else {
            log.error("Transaction op message write failed. messageId is {}, queueId is {}", msgExt.getMsgId(), msgExt.getQueueId());
            return false;
        }
    }

    @Override
    public OperationResult commitMessage(EndTransactionRequestHeader requestHeader) {
        // requestHeader.getCommitLogOffset()返回消息位移
        return getHalfMessageByOffset(requestHeader.getCommitLogOffset());
    }

    @Override
    public OperationResult rollbackMessage(EndTransactionRequestHeader requestHeader) {
        return getHalfMessageByOffset(requestHeader.getCommitLogOffset());
    }

    @Override
    public boolean open() {
        return true;
    }

    @Override
    public void close() {

    }

}
