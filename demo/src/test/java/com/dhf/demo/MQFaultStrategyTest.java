package com.dhf.demo;

import org.apache.rocketmq.client.impl.producer.TopicPublishInfo;
import org.apache.rocketmq.client.latency.MQFaultStrategy;
import org.apache.rocketmq.common.message.MessageQueue;
import org.apache.rocketmq.common.protocol.route.QueueData;
import org.apache.rocketmq.common.protocol.route.TopicRouteData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

public class MQFaultStrategyTest {
    public static void main(String[] args) {
        // 以该参数表示每次发送是成功还是失败，来分别观察MQFaultStrategy类在这两种情况下的不同
        boolean fail = false;
        System.out.println("假设每次发送" + (fail ? "失败" : "成功"));

        final MQFaultStrategy mqFaultStrategy = new MQFaultStrategy();
        mqFaultStrategy.setSendLatencyFaultEnable(true);

        final TopicPublishInfo topicPublishInfo = new TopicPublishInfo();
        topicPublishInfo.setHaveTopicRouterInfo(true);
        TopicRouteData topicRouteData = new TopicRouteData();
        topicRouteData.setQueueDatas(new ArrayList<>());
        topicPublishInfo.setMessageQueueList(new ArrayList<>());
        topicPublishInfo.setTopicRouteData(topicRouteData);

        Map<String, Integer> latencyMap = new HashMap<>();

        addBroker(topicPublishInfo, latencyMap, 30000, "topic-a", "broker1", 4);
        addBroker(topicPublishInfo, latencyMap, 50000, "topic-a", "broker2", 4);

        for (int i = 0; i < 10; i++) {
            System.out.println(String.format("第%d次开始循环", i));
            String lastBrokerName = null;
            for (int j = 0; j < 2; j++) {
                final MessageQueue messageQueue = mqFaultStrategy.selectOneMessageQueue(topicPublishInfo, lastBrokerName);
                lastBrokerName = messageQueue.getBrokerName();
                System.out.println(String.format("第%d次发送使用broker: %s", j, lastBrokerName));

                try {
                    sendMessage(messageQueue, fail);
                    mqFaultStrategy.updateFaultItem(lastBrokerName, latencyMap.get(lastBrokerName), false);
                    break;
                } catch (Exception e) {
                    mqFaultStrategy.updateFaultItem(lastBrokerName, latencyMap.get(lastBrokerName), true);
                }
            }
        }
    }

    private static void sendMessage(MessageQueue messageQueue, boolean fail) {
        if (fail) {
            throw new RuntimeException("发送失败");
        }
    }

    private static void addBroker(TopicPublishInfo topicPublishInfo, Map<String, Integer> latencyMap, int latency, String topic, String brokerName, int num) {
        QueueData queueData = new QueueData();
        queueData.setBrokerName(brokerName);
        queueData.setWriteQueueNums(num);
        queueData.setReadQueueNums(num);
        topicPublishInfo.getTopicRouteData().getQueueDatas().add(queueData);

        latencyMap.put(brokerName, latency);

        for (int i = 0; i < num; i++) {
            MessageQueue messageQueue = new MessageQueue();
            messageQueue.setBrokerName(brokerName);
            messageQueue.setQueueId(i);
            messageQueue.setTopic(topic);
            topicPublishInfo.getMessageQueueList().add(messageQueue);

        }
    }
}
