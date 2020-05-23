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

package org.apache.rocketmq.broker.filter;

import java.util.concurrent.ConcurrentMap;
import org.apache.rocketmq.broker.BrokerController;
import org.apache.rocketmq.broker.BrokerPathConfigHelper;
import org.apache.rocketmq.common.ConfigManager;
import org.apache.rocketmq.common.constant.LoggerName;
import org.apache.rocketmq.logging.InternalLogger;
import org.apache.rocketmq.logging.InternalLoggerFactory;
import org.apache.rocketmq.common.protocol.heartbeat.SubscriptionData;
import org.apache.rocketmq.filter.FilterFactory;
import org.apache.rocketmq.common.filter.ExpressionType;
import org.apache.rocketmq.filter.util.BloomFilter;
import org.apache.rocketmq.filter.util.BloomFilterData;
import org.apache.rocketmq.remoting.protocol.RemotingSerializable;

import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Consumer filter data manager.Just manage the consumers use expression filter.
 */
public class ConsumerFilterManager extends ConfigManager {

    private static final InternalLogger log = InternalLoggerFactory.getLogger(LoggerName.FILTER_LOGGER_NAME);

    private static final long MS_24_HOUR = 24 * 3600 * 1000;

    private ConcurrentMap<String/*Topic*/, FilterDataMapByTopic>
        filterDataByTopic = new ConcurrentHashMap<String/*consumer group*/, FilterDataMapByTopic>(256);

    private transient BrokerController brokerController;
    private transient BloomFilter bloomFilter;

    public ConsumerFilterManager() {
        // just for test
        this.bloomFilter = BloomFilter.createByFn(20, 64);
    }

    public ConsumerFilterManager(BrokerController brokerController) {
        this.brokerController = brokerController;
        this.bloomFilter = BloomFilter.createByFn(
            brokerController.getBrokerConfig().getMaxErrorRateOfBloomFilter(),
            brokerController.getBrokerConfig().getExpectConsumerNumUseFilter()
        );
        // then set bit map length of store config.
        brokerController.getMessageStoreConfig().setBitMapLengthConsumeQueueExt(
            this.bloomFilter.getM()
        );
    }

    /**
     * Build consumer filter data.Be care, bloom filter data is not included.
     *
     * @return maybe null
     */
    public static ConsumerFilterData build(final String topic, final String consumerGroup,
        final String expression, final String type,
        final long clientVersion) {
        if (ExpressionType.isTagType(type)) {
            return null;
        }

        ConsumerFilterData consumerFilterData = new ConsumerFilterData();
        consumerFilterData.setTopic(topic);
        consumerFilterData.setConsumerGroup(consumerGroup);
        consumerFilterData.setBornTime(System.currentTimeMillis());
        consumerFilterData.setDeadTime(0);
        consumerFilterData.setExpression(expression);
        consumerFilterData.setExpressionType(type);
        consumerFilterData.setClientVersion(clientVersion);
        try {
            // 根据type获取FilterSpi对象，并且编译表达式获取Expression对象，目前只支持ExpressionType.SQL92类型
            consumerFilterData.setCompiledExpression(
                FilterFactory.INSTANCE.get(type).compile(expression)
            );
        } catch (Throwable e) {
            log.error("parse error: expr={}, topic={}, group={}, error={}", expression, topic, consumerGroup, e.getMessage());
            return null;
        }

        return consumerFilterData;
    }

    public void register(final String consumerGroup, final Collection<SubscriptionData> subList) {
        // 遍历消费者发送的心跳中带来的订阅配置
        for (SubscriptionData subscriptionData : subList) {
            // 如果当前订阅配置没有过滤配置，或者过滤的方式为TAG，则直接返回，否则说明过滤的方式为SQL92，此时会为过滤表达式创建布隆过滤器
            // 并和过滤表达式一起保存到新建的ConsumerFilterData对象（如果没有的话），再将ConsumerFilterData对象保存到filterDataByTopic属性
            register(
                subscriptionData.getTopic(),
                consumerGroup,
                subscriptionData.getSubString(),
                subscriptionData.getExpressionType(),
                subscriptionData.getSubVersion()
            );
        }

        // make illegal topic dead.
        // ConsumerFilterData对象表示一个消费者组对一个topic的过滤配置，包含了布隆过滤器、过滤类型、过滤表达式等，目前只支持SQL92类型
        Collection<ConsumerFilterData> groupFilterData = getByGroup(consumerGroup);

        // groupFilterData包含了当前消费者组的所有topic下的配置，一个topic对应一个ConsumerFilterData对象
        Iterator<ConsumerFilterData> iterator = groupFilterData.iterator();
        while (iterator.hasNext()) {
            // 遍历ConsumerFilterData对象，判断ConsumerFilterData对象对应的topic是否包含在传入的subList中
            ConsumerFilterData filterData = iterator.next();

            boolean exist = false;
            for (SubscriptionData subscriptionData : subList) {
                if (subscriptionData.getTopic().equals(filterData.getTopic())) {
                    exist = true;
                    break;
                }
            }

            // 设置不再被订阅的topic对应的ConsumerFilterData对象的deadTime属性为当前时间，即禁用该ConsumerFilterData对象
            // 设置当前消费者组不再订阅的topic的SubscriptionData对象的deadTime属性，这里不直接删除该配置是因为组内的其他的消费者
            // 成员可能还要用，清除操作会在当前ConsumerFilterManager对象被定时任务持久化时做
            if (!exist && !filterData.isDead()) {
                filterData.setDeadTime(System.currentTimeMillis());
                log.info("Consumer filter changed: {}, make illegal topic dead:{}", consumerGroup, filterData);
            }
        }
    }

    public boolean register(final String topic, final String consumerGroup, final String expression,
        final String type, final long clientVersion) {
        if (ExpressionType.isTagType(type)) {
            return false;
        }

        if (expression == null || expression.length() == 0) {
            return false;
        }

        // FilterDataMapByTopic对象包含了一个topic在所有消费者组中的订阅配置
        FilterDataMapByTopic filterDataMapByTopic = this.filterDataByTopic.get(topic);

        if (filterDataMapByTopic == null) {
            FilterDataMapByTopic temp = new FilterDataMapByTopic(topic);
            FilterDataMapByTopic prev = this.filterDataByTopic.putIfAbsent(topic, temp);
            filterDataMapByTopic = prev != null ? prev : temp;
        }

        // 为当前消费者组创建布隆过滤器
        BloomFilterData bloomFilterData = bloomFilter.generate(consumerGroup + "#" + topic);

        return filterDataMapByTopic.register(consumerGroup, expression, type, bloomFilterData, clientVersion);
    }

    public void unRegister(final String consumerGroup) {
        for (String topic : filterDataByTopic.keySet()) {
            this.filterDataByTopic.get(topic).unRegister(consumerGroup);
        }
    }

    public ConsumerFilterData get(final String topic, final String consumerGroup) {
        if (!this.filterDataByTopic.containsKey(topic)) {
            return null;
        }
        if (this.filterDataByTopic.get(topic).getGroupFilterData().isEmpty()) {
            return null;
        }

        return this.filterDataByTopic.get(topic).getGroupFilterData().get(consumerGroup);
    }

    public Collection<ConsumerFilterData> getByGroup(final String consumerGroup) {
        Collection<ConsumerFilterData> ret = new HashSet<ConsumerFilterData>();

        Iterator<FilterDataMapByTopic> topicIterator = this.filterDataByTopic.values().iterator();
        while (topicIterator.hasNext()) {
            FilterDataMapByTopic filterDataMapByTopic = topicIterator.next();

            Iterator<ConsumerFilterData> filterDataIterator = filterDataMapByTopic.getGroupFilterData().values().iterator();

            while (filterDataIterator.hasNext()) {
                ConsumerFilterData filterData = filterDataIterator.next();

                if (filterData.getConsumerGroup().equals(consumerGroup)) {
                    ret.add(filterData);
                }
            }
        }

        return ret;
    }

    public final Collection<ConsumerFilterData> get(final String topic) {
        if (!this.filterDataByTopic.containsKey(topic)) {
            return null;
        }
        if (this.filterDataByTopic.get(topic).getGroupFilterData().isEmpty()) {
            return null;
        }

        return this.filterDataByTopic.get(topic).getGroupFilterData().values();
    }

    public BloomFilter getBloomFilter() {
        return bloomFilter;
    }

    @Override
    public String encode() {
        return encode(false);
    }

    @Override
    public String configFilePath() {
        if (this.brokerController != null) {
            return BrokerPathConfigHelper.getConsumerFilterPath(
                this.brokerController.getMessageStoreConfig().getStorePathRootDir()
            );
        }
        return BrokerPathConfigHelper.getConsumerFilterPath("./unit_test");
    }

    @Override
    public void decode(final String jsonString) {
        ConsumerFilterManager load = RemotingSerializable.fromJson(jsonString, ConsumerFilterManager.class);
        if (load != null && load.filterDataByTopic != null) {
            boolean bloomChanged = false;
            for (String topic : load.filterDataByTopic.keySet()) {
                FilterDataMapByTopic dataMapByTopic = load.filterDataByTopic.get(topic);
                if (dataMapByTopic == null) {
                    continue;
                }

                for (String group : dataMapByTopic.getGroupFilterData().keySet()) {

                    ConsumerFilterData filterData = dataMapByTopic.getGroupFilterData().get(group);

                    if (filterData == null) {
                        continue;
                    }

                    try {
                        filterData.setCompiledExpression(
                            FilterFactory.INSTANCE.get(filterData.getExpressionType()).compile(filterData.getExpression())
                        );
                    } catch (Exception e) {
                        log.error("load filter data error, " + filterData, e);
                    }

                    // check whether bloom filter is changed
                    // if changed, ignore the bit map calculated before.
                    if (!this.bloomFilter.isValid(filterData.getBloomFilterData())) {
                        bloomChanged = true;
                        log.info("Bloom filter is changed!So ignore all filter data persisted! {}, {}", this.bloomFilter, filterData.getBloomFilterData());
                        break;
                    }

                    log.info("load exist consumer filter data: {}", filterData);

                    if (filterData.getDeadTime() == 0) {
                        // we think all consumers are dead when load
                        long deadTime = System.currentTimeMillis() - 30 * 1000;
                        filterData.setDeadTime(
                            deadTime <= filterData.getBornTime() ? filterData.getBornTime() : deadTime
                        );
                    }
                }
            }

            if (!bloomChanged) {
                this.filterDataByTopic = load.filterDataByTopic;
            }
        }
    }

    @Override
    public String encode(final boolean prettyFormat) {
        // clean
        {
            clean();
        }
        return RemotingSerializable.toJson(this, prettyFormat);
    }

    public void clean() {
        Iterator<Map.Entry<String, FilterDataMapByTopic>> topicIterator = this.filterDataByTopic.entrySet().iterator();
        while (topicIterator.hasNext()) {
            Map.Entry<String, FilterDataMapByTopic> filterDataMapByTopic = topicIterator.next();

            Iterator<Map.Entry<String, ConsumerFilterData>> filterDataIterator
                = filterDataMapByTopic.getValue().getGroupFilterData().entrySet().iterator();

            while (filterDataIterator.hasNext()) {
                Map.Entry<String, ConsumerFilterData> filterDataByGroup = filterDataIterator.next();

                ConsumerFilterData filterData = filterDataByGroup.getValue();
                if (filterData.howLongAfterDeath() >= (this.brokerController == null ? MS_24_HOUR : this.brokerController.getBrokerConfig().getFilterDataCleanTimeSpan())) {
                    log.info("Remove filter consumer {}, died too long!", filterDataByGroup.getValue());
                    filterDataIterator.remove();
                }
            }

            if (filterDataMapByTopic.getValue().getGroupFilterData().isEmpty()) {
                log.info("Topic has no consumer, remove it! {}", filterDataMapByTopic.getKey());
                topicIterator.remove();
            }
        }
    }

    public ConcurrentMap<String, FilterDataMapByTopic> getFilterDataByTopic() {
        return filterDataByTopic;
    }

    public void setFilterDataByTopic(final ConcurrentHashMap<String, FilterDataMapByTopic> filterDataByTopic) {
        this.filterDataByTopic = filterDataByTopic;
    }

    public static class FilterDataMapByTopic {

        private ConcurrentMap<String/*consumer group*/, ConsumerFilterData>
            groupFilterData = new ConcurrentHashMap<String, ConsumerFilterData>();

        private String topic;

        public FilterDataMapByTopic() {
        }

        public FilterDataMapByTopic(String topic) {
            this.topic = topic;
        }

        public void unRegister(String consumerGroup) {
            if (!this.groupFilterData.containsKey(consumerGroup)) {
                return;
            }

            ConsumerFilterData data = this.groupFilterData.get(consumerGroup);

            if (data == null || data.isDead()) {
                return;
            }

            long now = System.currentTimeMillis();

            log.info("Unregister consumer filter: {}, deadTime: {}", data, now);

            data.setDeadTime(now);
        }

        public boolean register(String consumerGroup, String expression, String type, BloomFilterData bloomFilterData,
            long clientVersion) {
            // 获取当前消费者组在当前FilterDataMapByTopic对象表示的topic的订阅配置
            ConsumerFilterData old = this.groupFilterData.get(consumerGroup);

            if (old == null) {
                // 创建ConsumerFilterData对象，该对象保存了当前topic在当前消费者组内的指定type的过滤配置，创建ConsumerFilterData对
                // 象时还会编译传入的表达式，获取Expression对象，目前只支持SQL92类型
                ConsumerFilterData consumerFilterData = build(topic, consumerGroup, expression, type, clientVersion);
                if (consumerFilterData == null) { // 构建ConsumerFilterData对象失败（如表达式编译失败），直接返回
                    return false;
                }
                // 保存布隆过滤器
                consumerFilterData.setBloomFilterData(bloomFilterData);

                // 保存配置，注意使用的是putIfAbsent方法
                old = this.groupFilterData.putIfAbsent(consumerGroup, consumerFilterData);
                if (old == null) { // 保存成功
                    log.info("New consumer filter registered: {}", consumerFilterData);
                    return true;
                } else {
                    // 保存时如果已经存在一个ConsumerFilterData对象，并且比当前创建的版本更新
                    if (clientVersion <= old.getClientVersion()) {
                        // 如果更新的ConsumerFilterData对象的过滤类型变了，或者表达式变了，记录当前ConsumerFilterData对象被忽略的日志
                        // 如果过滤类型和表达式都没变，则当前ConsumerFilterData对象被忽略也没关系
                        if (!type.equals(old.getExpressionType()) || !expression.equals(old.getExpression())) {
                            log.warn("Ignore consumer({} : {}) filter(concurrent), because of version {} <= {}, but maybe info changed!old={}:{}, ignored={}:{}",
                                consumerGroup, topic,
                                clientVersion, old.getClientVersion(),
                                old.getExpressionType(), old.getExpression(),
                                type, expression);
                        }
                        // 如果已存在的ConsumerFilterData对象被设置为失效的，则重新激活配置
                        if (clientVersion == old.getClientVersion() && old.isDead()) {
                            reAlive(old);
                            return true;
                        }

                        return false;
                    } else { // 否则当前ConsumerFilterData对象更新，这里再次保存
                        this.groupFilterData.put(consumerGroup, consumerFilterData);
                        log.info("New consumer filter registered(concurrent): {}, old: {}", consumerFilterData, old);
                        return true;
                    }
                }
            } else { // 如果已经存在一个ConsumerFilterData对象
                if (clientVersion <= old.getClientVersion()) {
                    // 这里的处理和上面的处理是一样的
                    if (!type.equals(old.getExpressionType()) || !expression.equals(old.getExpression())) {
                        log.info("Ignore consumer({}:{}) filter, because of version {} <= {}, but maybe info changed!old={}:{}, ignored={}:{}",
                            consumerGroup, topic,
                            clientVersion, old.getClientVersion(),
                            old.getExpressionType(), old.getExpression(),
                            type, expression);
                    }
                    if (clientVersion == old.getClientVersion() && old.isDead()) {
                        reAlive(old);
                        return true;
                    }

                    return false;
                }

                // 运行到这说明当前ConsumerFilterData对象比原来的ConsumerFilterData对象版本新

                // 如果当前ConsumerFilterData对象的过滤类型或者表达式和已存在的不一样
                boolean change = !old.getExpression().equals(expression) || !old.getExpressionType().equals(type);
                // 或者布隆过滤器有发生变化
                if (old.getBloomFilterData() == null && bloomFilterData != null) {
                    change = true;
                }
                if (old.getBloomFilterData() != null && !old.getBloomFilterData().equals(bloomFilterData)) {
                    change = true;
                }

                // if subscribe data is changed, or consumer is died too long.
                if (change) {
                    // 创建ConsumerFilterData对象并保存
                    ConsumerFilterData consumerFilterData = build(topic, consumerGroup, expression, type, clientVersion);
                    if (consumerFilterData == null) {
                        // new expression compile error, remove old, let client report error.
                        this.groupFilterData.remove(consumerGroup);
                        return false;
                    }
                    consumerFilterData.setBloomFilterData(bloomFilterData);

                    this.groupFilterData.put(consumerGroup, consumerFilterData);

                    log.info("Consumer filter info change, old: {}, new: {}, change: {}",
                        old, consumerFilterData, change);

                    return true;
                } else {
                    // 如果新旧ConsumerFilterData对象的过滤配置一样，则更新版本即可
                    old.setClientVersion(clientVersion);
                    if (old.isDead()) {
                        reAlive(old);
                    }
                    return true;
                }
            }
        }

        protected void reAlive(ConsumerFilterData filterData) {
            long oldDeadTime = filterData.getDeadTime();
            filterData.setDeadTime(0);
            log.info("Re alive consumer filter: {}, oldDeadTime: {}", filterData, oldDeadTime);
        }

        public final ConsumerFilterData get(String consumerGroup) {
            return this.groupFilterData.get(consumerGroup);
        }

        public final ConcurrentMap<String, ConsumerFilterData> getGroupFilterData() {
            return this.groupFilterData;
        }

        public void setGroupFilterData(final ConcurrentHashMap<String, ConsumerFilterData> groupFilterData) {
            this.groupFilterData = groupFilterData;
        }

        public String getTopic() {
            return topic;
        }

        public void setTopic(final String topic) {
            this.topic = topic;
        }
    }
}
