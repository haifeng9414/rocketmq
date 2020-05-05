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
package org.apache.rocketmq.client.consumer.rebalance;

import java.util.ArrayList;
import java.util.List;
import org.apache.rocketmq.client.consumer.AllocateMessageQueueStrategy;
import org.apache.rocketmq.client.log.ClientLogger;
import org.apache.rocketmq.logging.InternalLogger;
import org.apache.rocketmq.common.message.MessageQueue;

/**
 * Average Hashing queue algorithm
 */
public class AllocateMessageQueueAveragely implements AllocateMessageQueueStrategy {
    private final InternalLogger log = ClientLogger.getLog();

    @Override
    public List<MessageQueue> allocate(String consumerGroup, String currentCID, List<MessageQueue> mqAll,
        List<String> cidAll) {
        // currentCID为当前消费者的clientId
        if (currentCID == null || currentCID.length() < 1) {
            throw new IllegalArgumentException("currentCID is empty");
        }
        // mqAll为正在负载均衡的某个topic的所有MessageQueue对象，即所有broker队列，RebalanceImpl类的rebalanceByTopic
        // 方法在调用AllocateMessageQueueStrategy的allocate方法之前对mqAll进行了排序，这一点很关键
        if (mqAll == null || mqAll.isEmpty()) {
            throw new IllegalArgumentException("mqAll is null or mqAll empty");
        }
        // cidAll为当前消费者组的所有成员ID，同样cidAll也被排序了
        if (cidAll == null || cidAll.isEmpty()) {
            throw new IllegalArgumentException("cidAll is null or cidAll empty");
        }

        List<MessageQueue> result = new ArrayList<MessageQueue>();
        // 理论上不会发送这个情况
        if (!cidAll.contains(currentCID)) {
            log.info("[BUG] ConsumerGroup: {} The consumerId: {} not in cidAll: {}",
                consumerGroup,
                currentCID,
                cidAll);
            return result;
        }

        // 获取当前消费者在cidAll中的位置
        int index = cidAll.indexOf(currentCID);
        // 队列的数量对消费者数量取模
        int mod = mqAll.size() % cidAll.size();
        /*
         计算平均分配的情况下当前消费者应该消费几个队列，如果队列的数量小于等于消费者的数量，则平均值为1，否则当前消费者需要分配的队列数量
         计算过程如下：

         类似分页的逻辑，消费者数量为页的大小，队列的数量为等待放入页的数据，上面的mod的值就是多余的不能被平均分配的数量
         下面的averageSize就是当前消费者应该消费的队列数量，该值的确定过程是，假设消费者数量为N，队列数量为M，则N个消费者每个先分配一个
         队列，此时剩余M - N个队列未分配，之后重复这个过程，直到剩余M % N个队列，此时无法给每个消费者都分配一个队列了，只有前M % N消费者
         能够多分配一个队列，这里的M % N的值实际上就是上面的mod的值，所以下面计算averageSize的值时有如下情况：
         当mod > 0 && index < mod，表示存在无法被平均分配的队列并且当前消费者是前M % N的消费者，此时当前消费者需要消费的队列数量是mqAll.size() / cidAll.size() + 1
         不满足上面的条件说明当前消费者不是前M % N的消费者，则消费的队列数量为mqAll.size() / cidAll.size()
         */
        int averageSize =
            mqAll.size() <= cidAll.size() ? 1 : (mod > 0 && index < mod ? mqAll.size() / cidAll.size()
                + 1 : mqAll.size() / cidAll.size());
        /*
         在理解averageSize的值是如何计算之后，下面的逻辑就很清晰了，将[0, averageSize)的队列分配给第一个队列，
         [averageSize, index * averageSize)的队列分配给第二个队列，以此类推，下面的startIndex就是当前消费者被分配的队列的索引，需要
         注意的是当前消费者在前M % N或者不在前M % N个，会影响startIndex的值，所以针对startIndex的值分情况处理了一下
         */
        int startIndex = (mod > 0 && index < mod) ? index * averageSize : index * averageSize + mod;
        // 获取当前消费者能够消费的队列索引的最大值（不包含）
        int range = Math.min(averageSize, mqAll.size() - startIndex);
        for (int i = 0; i < range; i++) {
            result.add(mqAll.get((startIndex + i) % mqAll.size()));
        }
        return result;
    }

    @Override
    public String getName() {
        return "AVG";
    }
}
