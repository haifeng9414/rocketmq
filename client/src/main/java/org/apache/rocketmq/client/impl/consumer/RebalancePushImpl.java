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

import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.apache.rocketmq.client.consumer.AllocateMessageQueueStrategy;
import org.apache.rocketmq.client.consumer.store.OffsetStore;
import org.apache.rocketmq.client.consumer.store.ReadOffsetType;
import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.client.impl.factory.MQClientInstance;
import org.apache.rocketmq.common.MixAll;
import org.apache.rocketmq.common.UtilAll;
import org.apache.rocketmq.common.consumer.ConsumeFromWhere;
import org.apache.rocketmq.common.message.MessageQueue;
import org.apache.rocketmq.common.protocol.heartbeat.ConsumeType;
import org.apache.rocketmq.common.protocol.heartbeat.MessageModel;
import org.apache.rocketmq.common.protocol.heartbeat.SubscriptionData;

public class RebalancePushImpl extends RebalanceImpl {
    private final static long UNLOCK_DELAY_TIME_MILLS = Long.parseLong(System.getProperty("rocketmq.client.unlockDelayTimeMills", "20000"));
    private final DefaultMQPushConsumerImpl defaultMQPushConsumerImpl;

    public RebalancePushImpl(DefaultMQPushConsumerImpl defaultMQPushConsumerImpl) {
        this(null, null, null, null, defaultMQPushConsumerImpl);
    }

    public RebalancePushImpl(String consumerGroup, MessageModel messageModel,
        AllocateMessageQueueStrategy allocateMessageQueueStrategy,
        MQClientInstance mQClientFactory, DefaultMQPushConsumerImpl defaultMQPushConsumerImpl) {
        super(consumerGroup, messageModel, allocateMessageQueueStrategy, mQClientFactory);
        this.defaultMQPushConsumerImpl = defaultMQPushConsumerImpl;
    }

    @Override
    // mqAll保存了这次负载均衡后分配到的所有MessageQueue，mqDivided保存了根据updateProcessQueueTableInRebalance方法计算
    // 得到的实际分配到的MessageQueue对象，mqDivided是mqAll的子集
    public void messageQueueChanged(String topic, Set<MessageQueue> mqAll, Set<MessageQueue> mqDivided) {
        /**
         * When rebalance result changed, should update subscription's version to notify broker.
         * Fix: inconsistency subscription may lead to consumer miss messages.
         */
        SubscriptionData subscriptionData = this.subscriptionInner.get(topic);
        long newVersion = System.currentTimeMillis();
        log.info("{} Rebalance changed, also update version: {}, {}", topic, subscriptionData.getSubVersion(), newVersion);
        // 设置订阅配置的版本为当前时间
        subscriptionData.setSubVersion(newVersion);

        int currentQueueCount = this.processQueueTable.size();
        if (currentQueueCount != 0) {
            // pullThresholdForTopic表示一个消费者消费的某个topic能够缓存的消息数量，用于消费者流控，为-1时表示不控制
            int pullThresholdForTopic = this.defaultMQPushConsumerImpl.getDefaultMQPushConsumer().getPullThresholdForTopic();
            if (pullThresholdForTopic != -1) {
                // pullThresholdForTopic是topic级别的，这里将pullThresholdForTopic除以currentQueueCount，得到队列级别的
                // 能够缓存的消息数量
                int newVal = Math.max(1, pullThresholdForTopic / currentQueueCount);
                log.info("The pullThresholdForQueue is changed from {} to {}",
                    this.defaultMQPushConsumerImpl.getDefaultMQPushConsumer().getPullThresholdForQueue(), newVal);
                // 更新pullThresholdForQueue
                this.defaultMQPushConsumerImpl.getDefaultMQPushConsumer().setPullThresholdForQueue(newVal);
            }

            // pullThresholdSizeForTopic表示一个消费者消费的某个topic能够缓存的消息总大小，用于消费者流控，为-1时表示不控制
            int pullThresholdSizeForTopic = this.defaultMQPushConsumerImpl.getDefaultMQPushConsumer().getPullThresholdSizeForTopic();
            if (pullThresholdSizeForTopic != -1) {
                // 更新队列级别的值
                int newVal = Math.max(1, pullThresholdSizeForTopic / currentQueueCount);
                log.info("The pullThresholdSizeForQueue is changed from {} to {}",
                    this.defaultMQPushConsumerImpl.getDefaultMQPushConsumer().getPullThresholdSizeForQueue(), newVal);
                // 更新pullThresholdSizeForQueue
                this.defaultMQPushConsumerImpl.getDefaultMQPushConsumer().setPullThresholdSizeForQueue(newVal);
            }
        }

        // notify broker
        // 向broker发送心跳，因为当前消费者的SubscriptionData的版本变化了，需要告知broker
        this.getmQClientFactory().sendHeartbeatToAllBrokerWithLock();
    }

    @Override
    public boolean removeUnnecessaryMessageQueue(MessageQueue mq, ProcessQueue pq) {
        // 持久化即将被移除的MessageQueue的消费位移，对于RemoteBrokerOffsetStore的实现，就是将消费位移提交到broker
        this.defaultMQPushConsumerImpl.getOffsetStore().persist(mq);
        // 清除offsetStore中MessageQueue的数据
        this.defaultMQPushConsumerImpl.getOffsetStore().removeOffset(mq);
        // 如果是顺序消息并且是集群模式
        if (this.defaultMQPushConsumerImpl.isConsumeOrderly()
            && MessageModel.CLUSTERING.equals(this.defaultMQPushConsumerImpl.messageModel())) {
            try {
                // 尝试获取锁，最多等待1秒
                if (pq.getLockConsume().tryLock(1000, TimeUnit.MILLISECONDS)) {
                    try {
                        // 发送解锁consumeQueue的请求到broker
                        return this.unlockDelay(mq, pq);
                    } finally {
                        pq.getLockConsume().unlock();
                    }
                } else {
                    log.warn("[WRONG]mq is consuming, so can not unlock it, {}. maybe hanged for a while, {}",
                        mq,
                        pq.getTryUnlockTimes());

                    // 如果加锁失败，说明当前MessageQueue的消息还在被消费中，此时记录加锁失败的次数，不做其他操作
                    // 获取锁时已经尝试等待1秒了，所以通常不会发生这种情况，如果发生了，只能等下次负载均衡时再来一遍
                    // 解锁逻辑
                    pq.incTryUnlockTimes();
                }
            } catch (Exception e) {
                log.error("removeUnnecessaryMessageQueue Exception", e);
            }

            return false;
        }
        return true;
    }

    private boolean unlockDelay(final MessageQueue mq, final ProcessQueue pq) {

        // 判断当前ProcessQueue对象中是否还有从broker拉取下来后还没有消费完的消息
        if (pq.hasTempMessage()) {
            log.info("[{}]unlockDelay, begin {} ", mq.hashCode(), mq);
            // 如果有还没消费完的消息就20秒后执行unlock，在执行unlockDelay方法之前ProcessQueue对象的dropped属性已经被设置为true了，
            // 所以正在被ConsumeMessageOrderlyService消费的消息没法取消（正在被用户编写的consumeMessage方法消费），但是剩余的在
            // ProcessQueue中的消息不会再被消费了，此时只需要隔一段时间重新解锁即可，没必要重试。当然当前正在被消费的消息可能消费成功
            // 也可能消费失败，如果消费成功的话需要提交消费位移，否则消息顺序还是没法保证。所以ConsumeMessageOrderlyService必须要
            // 在dropped属性是否为true的情况下也要提交位移，而ConsumeMessageOrderlyService类的实现也确实这么做了（这点不同于
            // ConsumeMessageConcurrentlyService的实现，ConsumeMessageConcurrentlyService类在提交位移之前会判断dropped属性是否
            // 为false），同时在ConsumeMessageOrderlyService提交消费位移之前不会释放队列锁（20秒内不会），这就保证了消息的顺序
            this.defaultMQPushConsumerImpl.getmQClientFactory().getScheduledExecutorService().schedule(new Runnable() {
                @Override
                public void run() {
                    log.info("[{}]unlockDelay, execute at once {}", mq.hashCode(), mq);
                    RebalancePushImpl.this.unlock(mq, true);
                }
            }, UNLOCK_DELAY_TIME_MILLS, TimeUnit.MILLISECONDS);
        } else {
            // 否则直接执行unlock
            this.unlock(mq, true);
        }
        return true;
    }

    @Override
    public ConsumeType consumeType() {
        return ConsumeType.CONSUME_PASSIVELY;
    }

    @Override
    public void removeDirtyOffset(final MessageQueue mq) {
        this.defaultMQPushConsumerImpl.getOffsetStore().removeOffset(mq);
    }

    @Override
    public long computePullFromWhere(MessageQueue mq) {
        long result = -1;
        final ConsumeFromWhere consumeFromWhere = this.defaultMQPushConsumerImpl.getDefaultMQPushConsumer().getConsumeFromWhere();
        final OffsetStore offsetStore = this.defaultMQPushConsumerImpl.getOffsetStore();
        switch (consumeFromWhere) {
            case CONSUME_FROM_LAST_OFFSET_AND_FROM_MIN_WHEN_BOOT_FIRST:
            case CONSUME_FROM_MIN_OFFSET:
            case CONSUME_FROM_MAX_OFFSET:
            case CONSUME_FROM_LAST_OFFSET: {
                // 对于RemoteBrokerOffsetStore的实现，readOffset方法会从broker的ConsumerOffsetManager获取当前MessageQueue
                // 对应的consumeQueue被消费者提交的最新的消费位移，即获取当前MessageQueue对应的consumeQueue的最新被提交的消费位移
                long lastOffset = offsetStore.readOffset(mq, ReadOffsetType.READ_FROM_STORE);
                if (lastOffset >= 0) {
                    result = lastOffset;
                }
                // First start,no offset
                // 如果获取不到，可能是rocketmq刚刚启动，没有位移数据，此时用下面的方式获取消息位移
                else if (-1 == lastOffset) {
                    if (mq.getTopic().startsWith(MixAll.RETRY_GROUP_TOPIC_PREFIX)) {
                        result = 0L;
                    } else {
                        try {
                            // mq表示某个broker中的consumeQueue，这里获取该mq对应的consumeQueue已经保存的消息
                            // 的最大位移，从这个位移开始消费就能从最新的消息开始消费
                            result = this.mQClientFactory.getMQAdminImpl().maxOffset(mq);
                        } catch (MQClientException e) {
                            result = -1;
                        }
                    }
                } else {
                    result = -1;
                }
                break;
            }
            case CONSUME_FROM_FIRST_OFFSET: {
                // 和上面的实现方式一样，只不过是没有找到最新的被提交的消费位移时返回0
                long lastOffset = offsetStore.readOffset(mq, ReadOffsetType.READ_FROM_STORE);
                if (lastOffset >= 0) {
                    result = lastOffset;
                } else if (-1 == lastOffset) {
                    result = 0L;
                } else {
                    result = -1;
                }
                break;
            }
            case CONSUME_FROM_TIMESTAMP: {
                // 先获取最新的被提交的消费位移，如果获取到了则直接返回，否则根据时间戳获取消费位移，尽可能的减少消息被重复消费的次数
                long lastOffset = offsetStore.readOffset(mq, ReadOffsetType.READ_FROM_STORE);
                if (lastOffset >= 0) {
                    result = lastOffset;
                } else if (-1 == lastOffset) {
                    if (mq.getTopic().startsWith(MixAll.RETRY_GROUP_TOPIC_PREFIX)) {
                        try {
                            result = this.mQClientFactory.getMQAdminImpl().maxOffset(mq);
                        } catch (MQClientException e) {
                            result = -1;
                        }
                    } else {
                        try {
                            long timestamp = UtilAll.parseDate(this.defaultMQPushConsumerImpl.getDefaultMQPushConsumer().getConsumeTimestamp(),
                                UtilAll.YYYYMMDDHHMMSS).getTime();
                            result = this.mQClientFactory.getMQAdminImpl().searchOffset(mq, timestamp);
                        } catch (MQClientException e) {
                            result = -1;
                        }
                    }
                } else {
                    result = -1;
                }
                break;
            }

            default:
                break;
        }

        return result;
    }

    @Override
    public void dispatchPullRequest(List<PullRequest> pullRequestList) {
        for (PullRequest pullRequest : pullRequestList) {
            // 将PullRequest交给defaultMQPushConsumerImpl，触发拉取消息的过程
            this.defaultMQPushConsumerImpl.executePullRequestImmediately(pullRequest);
            log.info("doRebalance, {}, add a new pull request {}", consumerGroup, pullRequest);
        }
    }
}
