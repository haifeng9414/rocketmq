package org.apache.rocketmq.example.dhf.simple;

import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.example.dhf.AbstractSimpleExample;

public class SimpleSyncExample extends AbstractSimpleExample {

    public static void main(String[] args) throws Exception {
        SimpleSyncExample example = new SimpleSyncExample();
        example.producer();
        example.consumer();
    }

    private void producer() throws Exception {
        DefaultMQProducer producer = new DefaultMQProducer(GROUP_NAME);
        producer.setNamesrvAddr(NAME_SERVER);
        producer.start();

        for (int i = 0; i < 100; i++) {
            Message message = new Message(TOPIC, TAG, getMessage("HELLO " + i));
            final SendResult sendResult = producer.send(message);
            System.out.println(sendResult);
        }

        producer.shutdown();
    }

    private void consumer() throws Exception {
        DefaultMQPushConsumer consumer = new DefaultMQPushConsumer(GROUP_NAME);
        consumer.setNamesrvAddr(NAME_SERVER);
        consumer.subscribe(TOPIC, "*");

        consumer.registerMessageListener((MessageListenerConcurrently) (msgs, context) -> {
            System.out.printf("%s Receive New Messages: %s %n", Thread.currentThread().getName(), msgs);
            return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
        });

        consumer.start();
    }
}
