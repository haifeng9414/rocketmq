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

package org.apache.rocketmq.client.latency;

import org.apache.rocketmq.client.impl.producer.TopicPublishInfo;
import org.apache.rocketmq.client.log.ClientLogger;
import org.apache.rocketmq.logging.InternalLogger;
import org.apache.rocketmq.common.message.MessageQueue;

public class MQFaultStrategy {
    private final static InternalLogger log = ClientLogger.getLog();
    // 延迟故障容错，维护每个Broker的发送消息的延迟
    private final LatencyFaultTolerance<String> latencyFaultTolerance = new LatencyFaultToleranceImpl();

    // 发送消息延迟容错开关
    private boolean sendLatencyFaultEnable = false;

    // 延迟级别数组
    private long[] latencyMax = {50L, 100L, 550L, 1000L, 2000L, 3000L, 15000L};
    // 不可用时长数组，和延迟级别数组配合使用，使用逻辑是每次computeNotAvailableDuration方法计算延迟时间时，先根据发送请求的耗时
    // 在latencyMax数组中获取不小于耗时的数组位置，再返回该位置的notAvailableDuration数组元素的值作为延迟时间
    // 每次发送消息时无论成败都会调用updateFaultItem方法保存发送延迟相关信息，updateFaultItem方法会调用computeNotAvailableDuration
    // 方法计算被使用的broker下次可用时间
    private long[] notAvailableDuration = {0L, 0L, 30000L, 60000L, 120000L, 180000L, 600000L};

    public long[] getNotAvailableDuration() {
        return notAvailableDuration;
    }

    public void setNotAvailableDuration(final long[] notAvailableDuration) {
        this.notAvailableDuration = notAvailableDuration;
    }

    public long[] getLatencyMax() {
        return latencyMax;
    }

    public void setLatencyMax(final long[] latencyMax) {
        this.latencyMax = latencyMax;
    }

    public boolean isSendLatencyFaultEnable() {
        return sendLatencyFaultEnable;
    }

    public void setSendLatencyFaultEnable(final boolean sendLatencyFaultEnable) {
        this.sendLatencyFaultEnable = sendLatencyFaultEnable;
    }

    public MessageQueue selectOneMessageQueue(final TopicPublishInfo tpInfo, final String lastBrokerName) {
        if (this.sendLatencyFaultEnable) {
            try {
                // 获取并自增保存在ThreadLocal中的counter值
                int index = tpInfo.getSendWhichQueue().getAndIncrement();
                for (int i = 0; i < tpInfo.getMessageQueueList().size(); i++) {
                    // 用index对队列长度取余，选取一个位置
                    int pos = Math.abs(index++) % tpInfo.getMessageQueueList().size();
                    if (pos < 0)
                        pos = 0;
                    // 获取该位置的队列
                    MessageQueue mq = tpInfo.getMessageQueueList().get(pos);
                    // latencyFaultTolerance对象保存了若干broker的延迟时间，这里判断是否存在当前broker的延迟时间，如果不存在则
                    // 表示该broker还没有被使用过，isAvailable方法会返回true，否则isAvailable方法会根据broker的延迟信息判断broker
                    // 是否可用
                    if (latencyFaultTolerance.isAvailable(mq.getBrokerName())) {
                        // 根据sendDefaultImpl方法的逻辑，重试的时候lastBrokerName是上次失败的brokerName，非重试的时候lastBrokerName
                        // 为null
                        // 这里在lastBrokerName为null时直接返回队列，即在非重试的情况下直接使用轮询到的队列
                        // 或者如果上次使用的broker和这次选中的队列的broker名称相同，则直接返回，这也是在isAvailable为true的情况下
                        // 个人认为当lastBrokerName不为null并且mq.getBrokerName().equals(lastBrokerName)时，上面的isAvailable
                        // 方法应该时返回false才对，不确定什么时候会出现这种情况
                        if (null == lastBrokerName || mq.getBrokerName().equals(lastBrokerName))
                            return mq;
                    }
                }

                /*
                 执行到这里有两种可能性：
                 1. 上面轮询选择的队列的broker的延迟信息还没有保存在latencyFaultTolerance中，即选中的队列所在的broker是第一次使用
                 2. broker延迟信息在latencyFaultTolerance中，即broker被使用过，此时说明上一次选中的broker和这次选中的队列所在的broker
                 名称不同，即上次选中的broker的队列都轮询完了

                 上面两种情况都需要选择一个broker，latencyFaultTolerance的pickOneAtLeast方法根据以往的broker延迟信息选择一个broker
                 如果latencyFaultTolerance对象中还没有broker的延迟信息，则pickOneAtLeast方法会返回null
                */
                final String notBestBroker = latencyFaultTolerance.pickOneAtLeast();
                // 获取当前topic在该broker中的写队列数量
                int writeQueueNums = tpInfo.getQueueIdByBroker(notBestBroker);
                // 当notBestBroker为null时，writeQueueNums = -1，否则notBestBroker就是根据latencyFaultTolerance对象中的broker
                // 延迟信息选择的一个延迟相对较少的broker，此时将使用该broker
                if (writeQueueNums > 0) {
                    // 轮询选择一个队列并返回
                    final MessageQueue mq = tpInfo.selectOneMessageQueue();
                    if (notBestBroker != null) {
                        mq.setBrokerName(notBestBroker);
                        mq.setQueueId(tpInfo.getSendWhichQueue().getAndIncrement() % writeQueueNums);
                    }
                    return mq;
                } else {
                    latencyFaultTolerance.remove(notBestBroker);
                }
            } catch (Exception e) {
                log.error("Error occurred when selecting message queue", e);
            }

            // 选择失败则直接轮询选择一个队列
            return tpInfo.selectOneMessageQueue();
        }

        // 如果没有开启发送消息延迟容错开关，则不修改上次使用的broker
        return tpInfo.selectOneMessageQueue(lastBrokerName);
    }

    // 更新延迟容错信息，，发送消息后会调用该方法，当发送消息成功或由于发生中断
    // 导致发送过程结束时isolation为false，其他异常时为true
    public void updateFaultItem(final String brokerName, final long currentLatency, boolean isolation) {
        if (this.sendLatencyFaultEnable) {
            long duration = computeNotAvailableDuration(isolation ? 30000 : currentLatency);
            this.latencyFaultTolerance.updateFaultItem(brokerName, currentLatency, duration);
        }
    }

    // 计算延迟对应的不可用时间
    private long computeNotAvailableDuration(final long currentLatency) {
        for (int i = latencyMax.length - 1; i >= 0; i--) {
            if (currentLatency >= latencyMax[i])
                return this.notAvailableDuration[i];
        }

        return 0;
    }
}
