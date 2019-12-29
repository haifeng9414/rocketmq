package org.apache.rocketmq.example.dhf.simple;

import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendCallback;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.example.dhf.AbstractSimpleExample;

import java.util.concurrent.CountDownLatch;

public class SimpleAsyncExample extends AbstractSimpleExample {

    public static void main(String[] args) throws Exception {
        SimpleAsyncExample example = new SimpleAsyncExample();
        example.producer();
        example.consumer();
    }

    private void producer() throws Exception {
        DefaultMQProducer producer = new DefaultMQProducer(GROUP_NAME);
        producer.setNamesrvAddr(NAME_SERVER);
        producer.start();
        producer.setRetryTimesWhenSendAsyncFailed(0);

        int count = 100;
        CountDownLatch countDownLatch = new CountDownLatch(count);
        for (int i = 0; i < count; i++) {
            final int index = i;
            Message message = new Message(TOPIC, TAG, getMessage("HELLO " + i));
            producer.send(message, new SendCallback() {
                @Override
                public void onSuccess(SendResult sendResult) {
                    System.out.printf("%-10d OK %s %n", index, sendResult.getMsgId());
                    countDownLatch.countDown();
                }

                @Override
                public void onException(Throwable e) {
                    System.out.printf("%-10d Exception %s %n", index, e);
                    e.printStackTrace();
                    countDownLatch.countDown();
                }
            });
        }

        countDownLatch.await();
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
