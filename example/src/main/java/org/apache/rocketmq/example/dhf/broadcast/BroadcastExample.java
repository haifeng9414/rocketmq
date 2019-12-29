package org.apache.rocketmq.example.dhf.broadcast;

import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.common.consumer.ConsumeFromWhere;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.common.protocol.heartbeat.MessageModel;
import org.apache.rocketmq.example.dhf.AbstractSimpleExample;

public class BroadcastExample extends AbstractSimpleExample {
    public static void main(String[] args) throws Exception {
        final BroadcastExample example = new BroadcastExample();
        example.producer();
        example.consumer();
    }

    private void producer() throws Exception {
        DefaultMQProducer producer = new DefaultMQProducer(GROUP_NAME);
        producer.setNamesrvAddr(NAME_SERVER);
        producer.start();

        for (int i = 0; i < 5; i++) {
            Message message = new Message(TOPIC, TAG, getMessage("HELLO " + i));
            final SendResult sendResult = producer.send(message);
            System.out.println(sendResult);
        }

        producer.shutdown();
    }

    private void consumer() throws Exception {
        DefaultMQPushConsumer consumer = new DefaultMQPushConsumer(GROUP_NAME);
        consumer.setNamesrvAddr(NAME_SERVER);
        consumer.setConsumeFromWhere(ConsumeFromWhere.CONSUME_FROM_FIRST_OFFSET);
        consumer.subscribe(TOPIC, TAG);
        consumer.setMessageModel(MessageModel.BROADCASTING);

        consumer.registerMessageListener((MessageListenerConcurrently) (msgs, context) -> {
            System.out.printf("%s Receive New Messages: %s %n", Thread.currentThread().getName(), msgs);
            return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
        });

        consumer.start();
    }
}
