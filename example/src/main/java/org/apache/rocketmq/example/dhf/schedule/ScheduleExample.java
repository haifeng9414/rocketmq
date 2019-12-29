package org.apache.rocketmq.example.dhf.schedule;

import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.example.dhf.AbstractSimpleExample;

public class ScheduleExample extends AbstractSimpleExample {

    public static void main(String[] args) throws Exception {
        final ScheduleExample example = new ScheduleExample();
        example.producer();
        example.consumer();
    }

    private void producer() throws Exception {
        DefaultMQProducer producer = new DefaultMQProducer(GROUP_NAME);
        producer.setNamesrvAddr(NAME_SERVER);
        producer.start();

        int totalMessagesToSend = 100;
        for (int i = 0; i < totalMessagesToSend; i++) {
            Message message = new Message(TOPIC, ("Hello scheduled message " + i).getBytes());
            // This message will be delivered to consumer 10 seconds later.
            message.setDelayTimeLevel(3);
            // Send the message
            producer.send(message);
        }

        // Shutdown producer after use.
        producer.shutdown();
    }

    private void consumer() throws Exception {
        DefaultMQPushConsumer consumer = new DefaultMQPushConsumer(GROUP_NAME);
        consumer.setNamesrvAddr(NAME_SERVER);
        consumer.subscribe(TOPIC, "*");

        consumer.registerMessageListener((MessageListenerConcurrently) (msgs, context) -> {
            for (MessageExt message : msgs) {
                // Print approximate delay time period
                System.out.println("Receive message[msgId=" + message.getMsgId() + "] "
                        + (System.currentTimeMillis() - message.getStoreTimestamp()) + "ms later");
            }
            return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
        });

        consumer.start();
        System.out.printf("Consumer Started.%n");
    }
}
