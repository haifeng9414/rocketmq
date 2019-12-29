package org.apache.rocketmq.example.dhf.order;

import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeOrderlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerOrderly;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.common.consumer.ConsumeFromWhere;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.example.dhf.AbstractSimpleExample;

import java.util.ArrayList;
import java.util.List;

public class OrderedExample extends AbstractSimpleExample {

    public static void main(String[] args) throws Exception {
        final OrderedExample example = new OrderedExample();
        example.producer();
        example.consumer();
    }

    private void producer() throws Exception {
        DefaultMQProducer producer = new DefaultMQProducer(GROUP_NAME);
        producer.setNamesrvAddr(NAME_SERVER);
        producer.start();

        String[] tags = new String[]{"TagA", "TagB"};
        final List<Order> orders = buildOrders();
        for (final Order order : orders) {
            final byte[] body = getMessage("Hello RocketMQ " + order);

            Message message = new Message(TOPIC, tags[order.getOrderId() % tags.length], "KEY" + order.getOrderId(), body);
            final SendResult sendResult = producer.send(message, (mqs, msg, arg) -> {
                Integer id = (Integer) arg;
                int index = id % mqs.size();
                return mqs.get(index);
            }, order.getOrderId());

            System.out.println(sendResult + "， body: " + new String(body));
        }

        producer.shutdown();
    }

    private void consumer() throws Exception {
        DefaultMQPushConsumer consumer = new DefaultMQPushConsumer(GROUP_NAME);
        consumer.setNamesrvAddr(NAME_SERVER);
        consumer.setConsumeFromWhere(ConsumeFromWhere.CONSUME_FROM_FIRST_OFFSET);
        consumer.subscribe(TOPIC, "TagA");

        consumer.registerMessageListener((MessageListenerOrderly) (msgs, context) -> {
            System.out.printf("%s Receive New Messages: %d %s %s\n", Thread.currentThread().getName(), msgs.size(), msgs.get(0).getMsgId(), new String(msgs.get(0).getBody()));
            return ConsumeOrderlyStatus.SUCCESS;
        });

        consumer.start();
        System.out.printf("Consumer Started.%n");
    }

    private static class Order {
        private int orderId;
        private String desc;

        public int getOrderId() {
            return orderId;
        }

        public void setOrderId(int orderId) {
            this.orderId = orderId;
        }

        public String getDesc() {
            return desc;
        }

        public void setDesc(String desc) {
            this.desc = desc;
        }

        @Override
        public String toString() {
            return "Order{" +
                    "orderId=" + orderId +
                    ", desc='" + desc + '\'' +
                    '}';
        }
    }

    /**
     * 生成模拟订单数据
     */
    private static List<Order> buildOrders() {
        List<Order> orderList = new ArrayList<>();

        String[] orderDesc = new String[]{"创建", "付款", "推送", "完成"};
        for (int i = 0; i < 10; i++) {
            for (String desc : orderDesc) {
                Order order = new Order();
                order.setOrderId(i);
                order.setDesc(desc);
                orderList.add(order);
            }
        }

        return orderList;
    }
}
