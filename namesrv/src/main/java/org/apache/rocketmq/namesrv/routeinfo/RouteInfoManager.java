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
package org.apache.rocketmq.namesrv.routeinfo;

import io.netty.channel.Channel;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.apache.rocketmq.common.DataVersion;
import org.apache.rocketmq.common.MixAll;
import org.apache.rocketmq.common.TopicConfig;
import org.apache.rocketmq.common.constant.LoggerName;
import org.apache.rocketmq.common.constant.PermName;
import org.apache.rocketmq.logging.InternalLogger;
import org.apache.rocketmq.logging.InternalLoggerFactory;
import org.apache.rocketmq.common.namesrv.RegisterBrokerResult;
import org.apache.rocketmq.common.protocol.body.ClusterInfo;
import org.apache.rocketmq.common.protocol.body.TopicConfigSerializeWrapper;
import org.apache.rocketmq.common.protocol.body.TopicList;
import org.apache.rocketmq.common.protocol.route.BrokerData;
import org.apache.rocketmq.common.protocol.route.QueueData;
import org.apache.rocketmq.common.protocol.route.TopicRouteData;
import org.apache.rocketmq.common.sysflag.TopicSysFlag;
import org.apache.rocketmq.remoting.common.RemotingUtil;

public class RouteInfoManager {
    private static final InternalLogger log = InternalLoggerFactory.getLogger(LoggerName.NAMESRV_LOGGER_NAME);
    private final static long BROKER_CHANNEL_EXPIRED_TIME = 1000 * 60 * 2;
    private final ReadWriteLock lock = new ReentrantReadWriteLock();
    // 保存topic下的队列信息
    private final HashMap<String/* topic */, List<QueueData>> topicQueueTable;
    // 保存broker信息，这里的key是brokerName而不是brokerAddr，一个brokerName可能对应有多个节点，包括master和slave，
    // 这些节点的brokerName是相等的，但是IP不同，所以brokerName对应的是一个broker的所有节点，而BrokerData就保存了这些
    // 节点的信息
    private final HashMap<String/* brokerName */, BrokerData> brokerAddrTable;
    // 保存cluster下的brokerName
    private final HashMap<String/* clusterName */, Set<String/* brokerName */>> clusterAddrTable;
    // 保存brokerAddr和broker信息，一个broker下可能有master和slave，所以一个broker下可能有多个brokerAddr，每个brokerAddr
    // 对应一个节点，BrokerLiveInfo对象保存了某个节点的信息
    private final HashMap<String/* brokerAddr */, BrokerLiveInfo> brokerLiveTable;
    // 保存broker下的filter server
    private final HashMap<String/* brokerAddr */, List<String>/* Filter Server */> filterServerTable;

    public RouteInfoManager() {
        this.topicQueueTable = new HashMap<String, List<QueueData>>(1024);
        this.brokerAddrTable = new HashMap<String, BrokerData>(128);
        this.clusterAddrTable = new HashMap<String, Set<String>>(32);
        this.brokerLiveTable = new HashMap<String, BrokerLiveInfo>(256);
        this.filterServerTable = new HashMap<String, List<String>>(256);
    }

    public byte[] getAllClusterInfo() {
        ClusterInfo clusterInfoSerializeWrapper = new ClusterInfo();
        clusterInfoSerializeWrapper.setBrokerAddrTable(this.brokerAddrTable);
        clusterInfoSerializeWrapper.setClusterAddrTable(this.clusterAddrTable);
        return clusterInfoSerializeWrapper.encode();
    }

    // 删除topic只需要删除topicQueueTable里的key即可
    public void deleteTopic(final String topic) {
        try {
            try {
                this.lock.writeLock().lockInterruptibly();
                this.topicQueueTable.remove(topic);
            } finally {
                this.lock.writeLock().unlock();
            }
        } catch (Exception e) {
            log.error("deleteTopic Exception", e);
        }
    }

    // 获取所有的topic
    public byte[] getAllTopicList() {
        TopicList topicList = new TopicList();
        try {
            try {
                this.lock.readLock().lockInterruptibly();
                topicList.getTopicList().addAll(this.topicQueueTable.keySet());
            } finally {
                this.lock.readLock().unlock();
            }
        } catch (Exception e) {
            log.error("getAllTopicList Exception", e);
        }

        return topicList.encode();
    }

    // 注册broker
    public RegisterBrokerResult registerBroker(
        final String clusterName,
        final String brokerAddr,
        final String brokerName,
        final long brokerId,
        final String haServerAddr,
        final TopicConfigSerializeWrapper topicConfigWrapper,
        final List<String> filterServerList,
        final Channel channel) {
        RegisterBrokerResult result = new RegisterBrokerResult();
        try {
            try {
                this.lock.writeLock().lockInterruptibly();

                // 获取集群对应的broker集合
                Set<String> brokerNames = this.clusterAddrTable.get(clusterName);
                if (null == brokerNames) {
                    brokerNames = new HashSet<String>();
                    this.clusterAddrTable.put(clusterName, brokerNames);
                }
                // 保存注册的broker的name到集群的broker集合
                brokerNames.add(brokerName);

                boolean registerFirst = false;

                BrokerData brokerData = this.brokerAddrTable.get(brokerName);
                // 如果不存在当前正在注册的broker的信息，则表示是第一次注册
                if (null == brokerData) {
                    registerFirst = true;
                    // 最后的map是HashMap<Long/* brokerId */, String/* broker address */> brokerAddrs
                    brokerData = new BrokerData(clusterName, brokerName, new HashMap<Long, String>());
                    this.brokerAddrTable.put(brokerName, brokerData);
                }
                Map<Long, String> brokerAddrsMap = brokerData.getBrokerAddrs();
                //Switch slave to master: first remove <1, IP:PORT> in namesrv, then add <0, IP:PORT>
                //The same IP:PORT must only have one record in brokerAddrTable
                Iterator<Entry<Long, String>> it = brokerAddrsMap.entrySet().iterator();
                // 遍历之前的broker信息中的brokerId和brokerAddr的映射，如果当前注册的brokerAddr在之前的映射中存在并且brokerId不
                // 相同，则删除之前的映射
                while (it.hasNext()) {
                    Entry<Long, String> item = it.next();
                    if (null != brokerAddr && brokerAddr.equals(item.getValue()) && brokerId != item.getKey()) {
                        it.remove();
                    }
                }

                // 更新/保存brokerId和brokerAddr的映射关系
                String oldAddr = brokerData.getBrokerAddrs().put(brokerId, brokerAddr);
                registerFirst = registerFirst || (null == oldAddr);

                // topicConfigWrapper保存的是topicName和topicConfig的映射关系
                if (null != topicConfigWrapper
                    && MixAll.MASTER_ID == brokerId) { // 如果是master节点
                    // 判断之前的和现在的dataVersion不相等，或者如果是第一次注册brokerId和brokerAddr的关系
                    // 每个broker节点自己维护dataVersion，每次broker节点的topic有更新时，其将dataVersion + 1
                    // 这里发现dataVersion没有变化就不做更新操作，而broker节点也就没必要在心跳中包含topic信息占用带宽
                    if (this.isBrokerTopicConfigChanged(brokerAddr, topicConfigWrapper.getDataVersion())
                        || registerFirst) {
                        ConcurrentMap<String, TopicConfig> tcTable =
                            topicConfigWrapper.getTopicConfigTable();
                        if (tcTable != null) {
                            for (Map.Entry<String, TopicConfig> entry : tcTable.entrySet()) {
                                // topicConfig带有该topic在broker中的报配置，包括写队列和读队列的数量、topic的权限配置等信息，
                                // 这里根据这些信息创建queueData，并保存或更新topic在当前broker中的queueData
                                this.createAndUpdateQueueData(brokerName, entry.getValue());
                            }
                        }
                    }
                }

                // 直接更新一个新的BrokerLiveInfo对象
                BrokerLiveInfo prevBrokerLiveInfo = this.brokerLiveTable.put(brokerAddr,
                    new BrokerLiveInfo(
                        System.currentTimeMillis(),
                        topicConfigWrapper.getDataVersion(),
                        channel,
                        haServerAddr));
                if (null == prevBrokerLiveInfo) {
                    log.info("new broker registered, {} HAServer: {}", brokerAddr, haServerAddr);
                }

                // 更新broker的filterServerList
                if (filterServerList != null) {
                    if (filterServerList.isEmpty()) {
                        this.filterServerTable.remove(brokerAddr);
                    } else {
                        this.filterServerTable.put(brokerAddr, filterServerList);
                    }
                }

                // 如果是slave节点，将其对应的masterAddr保存到result
                if (MixAll.MASTER_ID != brokerId) {
                    String masterAddr = brokerData.getBrokerAddrs().get(MixAll.MASTER_ID);
                    if (masterAddr != null) {
                        BrokerLiveInfo brokerLiveInfo = this.brokerLiveTable.get(masterAddr);
                        if (brokerLiveInfo != null) {
                            result.setHaServerAddr(brokerLiveInfo.getHaServerAddr());
                            result.setMasterAddr(masterAddr);
                        }
                    }
                }
            } finally {
                this.lock.writeLock().unlock();
            }
        } catch (Exception e) {
            log.error("registerBroker Exception", e);
        }

        return result;
    }

    // 判断dataVersion是否不同，以此判断broker的配置是否变化
    public boolean isBrokerTopicConfigChanged(final String brokerAddr, final DataVersion dataVersion) {
        DataVersion prev = queryBrokerTopicConfig(brokerAddr);
        return null == prev || !prev.equals(dataVersion);
    }

    // 获取broker配置的dataVersion
    public DataVersion queryBrokerTopicConfig(final String brokerAddr) {
        BrokerLiveInfo prev = this.brokerLiveTable.get(brokerAddr);
        if (prev != null) {
            return prev.getDataVersion();
        }
        return null;
    }

    // 更新broker配置的时间戳
    public void updateBrokerInfoUpdateTimestamp(final String brokerAddr) {
        BrokerLiveInfo prev = this.brokerLiveTable.get(brokerAddr);
        if (prev != null) {
            prev.setLastUpdateTimestamp(System.currentTimeMillis());
        }
    }

    // 根据topicConfig创建或更新队列配置
    private void createAndUpdateQueueData(final String brokerName, final TopicConfig topicConfig) {
        QueueData queueData = new QueueData();
        queueData.setBrokerName(brokerName);
        queueData.setWriteQueueNums(topicConfig.getWriteQueueNums());
        queueData.setReadQueueNums(topicConfig.getReadQueueNums());
        queueData.setPerm(topicConfig.getPerm());
        queueData.setTopicSynFlag(topicConfig.getTopicSysFlag());

        // topicQueueTable为topic下的队列信息
        List<QueueData> queueDataList = this.topicQueueTable.get(topicConfig.getTopicName());
        if (null == queueDataList) {
            queueDataList = new LinkedList<QueueData>();
            queueDataList.add(queueData);
            this.topicQueueTable.put(topicConfig.getTopicName(), queueDataList);
            log.info("new topic registered, {} {}", topicConfig.getTopicName(), queueData);
        } else {
            boolean addNewOne = true;

            Iterator<QueueData> it = queueDataList.iterator();
            while (it.hasNext()) {
                QueueData qd = it.next();
                // 寻找topic下当前broker的队列配置
                if (qd.getBrokerName().equals(brokerName)) {
                    // 如果配置没有变化，则不需要保存新建的queueData对象
                    if (qd.equals(queueData)) {
                        addNewOne = false;
                    } else {
                        log.info("topic changed, {} OLD: {} NEW: {}", topicConfig.getTopicName(), qd,
                            queueData);
                        it.remove();
                    }
                }
            }

            if (addNewOne) {
                queueDataList.add(queueData);
            }
        }
    }

    public int wipeWritePermOfBrokerByLock(final String brokerName) {
        try {
            try {
                this.lock.writeLock().lockInterruptibly();
                return wipeWritePermOfBroker(brokerName);
            } finally {
                this.lock.writeLock().unlock();
            }
        } catch (Exception e) {
            log.error("wipeWritePermOfBrokerByLock Exception", e);
        }

        return 0;
    }

    // 更新指定broker下的所有topic下的所有队列的perm，增加写权限
    private int wipeWritePermOfBroker(final String brokerName) {
        int wipeTopicCnt = 0;
        Iterator<Entry<String, List<QueueData>>> itTopic = this.topicQueueTable.entrySet().iterator();
        while (itTopic.hasNext()) {
            Entry<String, List<QueueData>> entry = itTopic.next();
            List<QueueData> qdList = entry.getValue();

            Iterator<QueueData> it = qdList.iterator();
            while (it.hasNext()) {
                QueueData qd = it.next();
                if (qd.getBrokerName().equals(brokerName)) {
                    int perm = qd.getPerm();
                    perm &= ~PermName.PERM_WRITE;
                    qd.setPerm(perm);
                    wipeTopicCnt++;
                }
            }
        }

        return wipeTopicCnt;
    }

    // 注销某个broker的节点
    public void unregisterBroker(
        final String clusterName,
        final String brokerAddr,
        final String brokerName,
        final long brokerId) {
        try {
            try {
                this.lock.writeLock().lockInterruptibly();
                // 移除broker节点的配置
                BrokerLiveInfo brokerLiveInfo = this.brokerLiveTable.remove(brokerAddr);
                log.info("unregisterBroker, remove from brokerLiveTable {}, {}",
                    brokerLiveInfo != null ? "OK" : "Failed",
                    brokerAddr
                );

                // 移除broker节点的filter server配置
                this.filterServerTable.remove(brokerAddr);

                boolean removeBrokerName = false;
                BrokerData brokerData = this.brokerAddrTable.get(brokerName);
                if (null != brokerData) {
                    // 从broker的节点列表中删除当前节点
                    String addr = brokerData.getBrokerAddrs().remove(brokerId);
                    log.info("unregisterBroker, remove addr from brokerAddrTable {}, {}",
                        addr != null ? "OK" : "Failed",
                        brokerAddr
                    );

                    // 如果broker的节点为空则删除broker
                    if (brokerData.getBrokerAddrs().isEmpty()) {
                        this.brokerAddrTable.remove(brokerName);
                        log.info("unregisterBroker, remove name from brokerAddrTable OK, {}",
                            brokerName
                        );

                        removeBrokerName = true;
                    }
                }

                // 如果broker下的节点为空，则将broker从cluster的broker列表中删除并判断删除后的broker列表是否为空，
                // 如果是则删除cluster
                if (removeBrokerName) {
                    Set<String> nameSet = this.clusterAddrTable.get(clusterName);
                    if (nameSet != null) {
                        boolean removed = nameSet.remove(brokerName);
                        log.info("unregisterBroker, remove name from clusterAddrTable {}, {}",
                            removed ? "OK" : "Failed",
                            brokerName);

                        if (nameSet.isEmpty()) {
                            this.clusterAddrTable.remove(clusterName);
                            log.info("unregisterBroker, remove cluster from clusterAddrTable {}",
                                clusterName
                            );
                        }
                    }
                    // 删除broker下所有topic的队列信息
                    this.removeTopicByBrokerName(brokerName);
                }
            } finally {
                this.lock.writeLock().unlock();
            }
        } catch (Exception e) {
            log.error("unregisterBroker Exception", e);
        }
    }

    // 删除broker下所有topic的队列信息
    private void removeTopicByBrokerName(final String brokerName) {
        Iterator<Entry<String, List<QueueData>>> itMap = this.topicQueueTable.entrySet().iterator();
        while (itMap.hasNext()) {
            Entry<String, List<QueueData>> entry = itMap.next();

            String topic = entry.getKey();
            List<QueueData> queueDataList = entry.getValue();
            Iterator<QueueData> it = queueDataList.iterator();
            while (it.hasNext()) {
                QueueData qd = it.next();
                if (qd.getBrokerName().equals(brokerName)) {
                    log.info("removeTopicByBrokerName, remove one broker's topic {} {}", topic, qd);
                    it.remove();
                }
            }

            if (queueDataList.isEmpty()) {
                log.info("removeTopicByBrokerName, remove the topic all queue {}", topic);
                itMap.remove();
            }
        }
    }

    // 获取指定topic下所有队列信息、broker节点信息和filter server
    public TopicRouteData pickupTopicRouteData(final String topic) {
        TopicRouteData topicRouteData = new TopicRouteData();
        boolean foundQueueData = false;
        boolean foundBrokerData = false;
        Set<String> brokerNameSet = new HashSet<String>();
        List<BrokerData> brokerDataList = new LinkedList<BrokerData>();
        topicRouteData.setBrokerDatas(brokerDataList);

        HashMap<String, List<String>> filterServerMap = new HashMap<String, List<String>>();
        topicRouteData.setFilterServerTable(filterServerMap);

        try {
            try {
                this.lock.readLock().lockInterruptibly();
                List<QueueData> queueDataList = this.topicQueueTable.get(topic);
                if (queueDataList != null) {
                    // 保存topic下的队列信息
                    topicRouteData.setQueueDatas(queueDataList);
                    foundQueueData = true;

                    Iterator<QueueData> it = queueDataList.iterator();
                    // 遍历队列，获取所有的brokerName
                    while (it.hasNext()) {
                        QueueData qd = it.next();
                        brokerNameSet.add(qd.getBrokerName());
                    }

                    // 遍历所有broker对应的brokerData，保存所有的brokerData和filter server
                    for (String brokerName : brokerNameSet) {
                        BrokerData brokerData = this.brokerAddrTable.get(brokerName);
                        if (null != brokerData) {
                            BrokerData brokerDataClone = new BrokerData(brokerData.getCluster(), brokerData.getBrokerName(), (HashMap<Long, String>) brokerData
                                .getBrokerAddrs().clone());
                            brokerDataList.add(brokerDataClone);
                            foundBrokerData = true;
                            for (final String brokerAddr : brokerDataClone.getBrokerAddrs().values()) {
                                List<String> filterServerList = this.filterServerTable.get(brokerAddr);
                                filterServerMap.put(brokerAddr, filterServerList);
                            }
                        }
                    }
                }
            } finally {
                this.lock.readLock().unlock();
            }
        } catch (Exception e) {
            log.error("pickupTopicRouteData Exception", e);
        }

        log.debug("pickupTopicRouteData {} {}", topic, topicRouteData);

        if (foundBrokerData && foundQueueData) {
            return topicRouteData;
        }

        return null;
    }

    // 扫描所有broker节点，close所有过期的节点的channel
    public void scanNotActiveBroker() {
        Iterator<Entry<String, BrokerLiveInfo>> it = this.brokerLiveTable.entrySet().iterator();
        // 遍历所有broker的节点
        while (it.hasNext()) {
            Entry<String, BrokerLiveInfo> next = it.next();
            // 获取上次更新时间
            long last = next.getValue().getLastUpdateTimestamp();
            // 如果上次更新时间加上BROKER_CHANNEL_EXPIRED_TIME小于当前时间
            if ((last + BROKER_CHANNEL_EXPIRED_TIME) < System.currentTimeMillis()) {
                // 关闭channel
                RemotingUtil.closeChannel(next.getValue().getChannel());
                it.remove();
                log.warn("The broker channel expired, {} {}ms", next.getKey(), BROKER_CHANNEL_EXPIRED_TIME);
                // 删除brokerAddr的相关信息
                this.onChannelDestroy(next.getKey(), next.getValue().getChannel());
            }
        }
    }

    // 删除brokerAddr的相关信息
    public void onChannelDestroy(String remoteAddr, Channel channel) {
        String brokerAddrFound = null;
        if (channel != null) {
            try {
                try {
                    // 遍历所有broker节点获取channel对应的brokerAddr
                    this.lock.readLock().lockInterruptibly();
                    Iterator<Entry<String, BrokerLiveInfo>> itBrokerLiveTable =
                        this.brokerLiveTable.entrySet().iterator();
                    while (itBrokerLiveTable.hasNext()) {
                        Entry<String, BrokerLiveInfo> entry = itBrokerLiveTable.next();
                        if (entry.getValue().getChannel() == channel) {
                            brokerAddrFound = entry.getKey();
                            break;
                        }
                    }
                } finally {
                    this.lock.readLock().unlock();
                }
            } catch (Exception e) {
                log.error("onChannelDestroy Exception", e);
            }
        }

        if (null == brokerAddrFound) {
            brokerAddrFound = remoteAddr;
        } else {
            log.info("the broker's channel destroyed, {}, clean it's data structure at once", brokerAddrFound);
        }

        if (brokerAddrFound != null && brokerAddrFound.length() > 0) {

            try {
                try {
                    this.lock.writeLock().lockInterruptibly();
                    // 情况broker节点的相关信息
                    this.brokerLiveTable.remove(brokerAddrFound);
                    this.filterServerTable.remove(brokerAddrFound);
                    String brokerNameFound = null;
                    boolean removeBrokerName = false;
                    Iterator<Entry<String, BrokerData>> itBrokerAddrTable =
                        this.brokerAddrTable.entrySet().iterator();
                    while (itBrokerAddrTable.hasNext() && (null == brokerNameFound)) {
                        BrokerData brokerData = itBrokerAddrTable.next().getValue();

                        Iterator<Entry<Long, String>> it = brokerData.getBrokerAddrs().entrySet().iterator();
                        while (it.hasNext()) {
                            Entry<Long, String> entry = it.next();
                            Long brokerId = entry.getKey();
                            String brokerAddr = entry.getValue();
                            // 将brokerAddr从broker下的broker节点列表中删除
                            if (brokerAddr.equals(brokerAddrFound)) {
                                brokerNameFound = brokerData.getBrokerName();
                                it.remove();
                                log.info("remove brokerAddr[{}, {}] from brokerAddrTable, because channel destroyed",
                                    brokerId, brokerAddr);
                                break;
                            }
                        }

                        // 如果broker下的broker节点空了，则删除整个broker
                        if (brokerData.getBrokerAddrs().isEmpty()) {
                            removeBrokerName = true;
                            itBrokerAddrTable.remove();
                            log.info("remove brokerName[{}] from brokerAddrTable, because channel destroyed",
                                brokerData.getBrokerName());
                        }
                    }

                    // broker相关的清理工作
                    if (brokerNameFound != null && removeBrokerName) {
                        Iterator<Entry<String, Set<String>>> it = this.clusterAddrTable.entrySet().iterator();
                        while (it.hasNext()) {
                            Entry<String, Set<String>> entry = it.next();
                            String clusterName = entry.getKey();
                            Set<String> brokerNames = entry.getValue();
                            boolean removed = brokerNames.remove(brokerNameFound);
                            if (removed) {
                                log.info("remove brokerName[{}], clusterName[{}] from clusterAddrTable, because channel destroyed",
                                    brokerNameFound, clusterName);

                                if (brokerNames.isEmpty()) {
                                    log.info("remove the clusterName[{}] from clusterAddrTable, because channel destroyed and no broker in this cluster",
                                        clusterName);
                                    it.remove();
                                }

                                break;
                            }
                        }
                    }

                    // 如果删除了整个broker，则删除broker的队列信息
                    if (removeBrokerName) {
                        Iterator<Entry<String, List<QueueData>>> itTopicQueueTable =
                            this.topicQueueTable.entrySet().iterator();
                        while (itTopicQueueTable.hasNext()) {
                            Entry<String, List<QueueData>> entry = itTopicQueueTable.next();
                            String topic = entry.getKey();
                            List<QueueData> queueDataList = entry.getValue();

                            Iterator<QueueData> itQueueData = queueDataList.iterator();
                            while (itQueueData.hasNext()) {
                                QueueData queueData = itQueueData.next();
                                if (queueData.getBrokerName().equals(brokerNameFound)) {
                                    itQueueData.remove();
                                    log.info("remove topic[{} {}], from topicQueueTable, because channel destroyed",
                                        topic, queueData);
                                }
                            }

                            if (queueDataList.isEmpty()) {
                                itTopicQueueTable.remove();
                                log.info("remove topic[{}] all queue, from topicQueueTable, because channel destroyed",
                                    topic);
                            }
                        }
                    }
                } finally {
                    this.lock.writeLock().unlock();
                }
            } catch (Exception e) {
                log.error("onChannelDestroy Exception", e);
            }
        }
    }

    // 打印所有配置
    public void printAllPeriodically() {
        try {
            try {
                this.lock.readLock().lockInterruptibly();
                log.info("--------------------------------------------------------");
                {
                    log.info("topicQueueTable SIZE: {}", this.topicQueueTable.size());
                    Iterator<Entry<String, List<QueueData>>> it = this.topicQueueTable.entrySet().iterator();
                    while (it.hasNext()) {
                        Entry<String, List<QueueData>> next = it.next();
                        log.info("topicQueueTable Topic: {} {}", next.getKey(), next.getValue());
                    }
                }

                {
                    log.info("brokerAddrTable SIZE: {}", this.brokerAddrTable.size());
                    Iterator<Entry<String, BrokerData>> it = this.brokerAddrTable.entrySet().iterator();
                    while (it.hasNext()) {
                        Entry<String, BrokerData> next = it.next();
                        log.info("brokerAddrTable brokerName: {} {}", next.getKey(), next.getValue());
                    }
                }

                {
                    log.info("brokerLiveTable SIZE: {}", this.brokerLiveTable.size());
                    Iterator<Entry<String, BrokerLiveInfo>> it = this.brokerLiveTable.entrySet().iterator();
                    while (it.hasNext()) {
                        Entry<String, BrokerLiveInfo> next = it.next();
                        log.info("brokerLiveTable brokerAddr: {} {}", next.getKey(), next.getValue());
                    }
                }

                {
                    log.info("clusterAddrTable SIZE: {}", this.clusterAddrTable.size());
                    Iterator<Entry<String, Set<String>>> it = this.clusterAddrTable.entrySet().iterator();
                    while (it.hasNext()) {
                        Entry<String, Set<String>> next = it.next();
                        log.info("clusterAddrTable clusterName: {} {}", next.getKey(), next.getValue());
                    }
                }
            } finally {
                this.lock.readLock().unlock();
            }
        } catch (Exception e) {
            log.error("printAllPeriodically Exception", e);
        }
    }

    public byte[] getSystemTopicList() {
        TopicList topicList = new TopicList();
        try {
            try {
                this.lock.readLock().lockInterruptibly();
                for (Map.Entry<String, Set<String>> entry : clusterAddrTable.entrySet()) {
                    topicList.getTopicList().add(entry.getKey());
                    topicList.getTopicList().addAll(entry.getValue());
                }

                if (brokerAddrTable != null && !brokerAddrTable.isEmpty()) {
                    Iterator<String> it = brokerAddrTable.keySet().iterator();
                    while (it.hasNext()) {
                        BrokerData bd = brokerAddrTable.get(it.next());
                        HashMap<Long, String> brokerAddrs = bd.getBrokerAddrs();
                        if (brokerAddrs != null && !brokerAddrs.isEmpty()) {
                            Iterator<Long> it2 = brokerAddrs.keySet().iterator();
                            topicList.setBrokerAddr(brokerAddrs.get(it2.next()));
                            break;
                        }
                    }
                }
            } finally {
                this.lock.readLock().unlock();
            }
        } catch (Exception e) {
            log.error("getAllTopicList Exception", e);
        }

        return topicList.encode();
    }

    // 获取cluster下的所有topic的序列化值
    public byte[] getTopicsByCluster(String cluster) {
        TopicList topicList = new TopicList();
        try {
            try {
                this.lock.readLock().lockInterruptibly();
                Set<String> brokerNameSet = this.clusterAddrTable.get(cluster);
                for (String brokerName : brokerNameSet) {
                    Iterator<Entry<String, List<QueueData>>> topicTableIt =
                        this.topicQueueTable.entrySet().iterator();
                    while (topicTableIt.hasNext()) {
                        Entry<String, List<QueueData>> topicEntry = topicTableIt.next();
                        String topic = topicEntry.getKey();
                        List<QueueData> queueDatas = topicEntry.getValue();
                        for (QueueData queueData : queueDatas) {
                            if (brokerName.equals(queueData.getBrokerName())) {
                                topicList.getTopicList().add(topic);
                                break;
                            }
                        }
                    }
                }
            } finally {
                this.lock.readLock().unlock();
            }
        } catch (Exception e) {
            log.error("getAllTopicList Exception", e);
        }

        return topicList.encode();
    }

    // 返回所有topicSynFlag的最后一位是1的topic？
    public byte[] getUnitTopics() {
        TopicList topicList = new TopicList();
        try {
            try {
                this.lock.readLock().lockInterruptibly();
                Iterator<Entry<String, List<QueueData>>> topicTableIt =
                    this.topicQueueTable.entrySet().iterator();
                while (topicTableIt.hasNext()) {
                    Entry<String, List<QueueData>> topicEntry = topicTableIt.next();
                    String topic = topicEntry.getKey();
                    List<QueueData> queueDatas = topicEntry.getValue();
                    if (queueDatas != null && queueDatas.size() > 0
                        && TopicSysFlag.hasUnitFlag(queueDatas.get(0).getTopicSynFlag())) {
                        topicList.getTopicList().add(topic);
                    }
                }
            } finally {
                this.lock.readLock().unlock();
            }
        } catch (Exception e) {
            log.error("getAllTopicList Exception", e);
        }

        return topicList.encode();
    }

    // 返回所有topicSynFlag的倒数第二位是1的topic？
    public byte[] getHasUnitSubTopicList() {
        TopicList topicList = new TopicList();
        try {
            try {
                this.lock.readLock().lockInterruptibly();
                Iterator<Entry<String, List<QueueData>>> topicTableIt =
                    this.topicQueueTable.entrySet().iterator();
                while (topicTableIt.hasNext()) {
                    Entry<String, List<QueueData>> topicEntry = topicTableIt.next();
                    String topic = topicEntry.getKey();
                    List<QueueData> queueDatas = topicEntry.getValue();
                    if (queueDatas != null && queueDatas.size() > 0
                        && TopicSysFlag.hasUnitSubFlag(queueDatas.get(0).getTopicSynFlag())) {
                        topicList.getTopicList().add(topic);
                    }
                }
            } finally {
                this.lock.readLock().unlock();
            }
        } catch (Exception e) {
            log.error("getAllTopicList Exception", e);
        }

        return topicList.encode();
    }

    // 返回所有topicSynFlag的倒数第二位是1并且最后一位是0的topic？
    public byte[] getHasUnitSubUnUnitTopicList() {
        TopicList topicList = new TopicList();
        try {
            try {
                this.lock.readLock().lockInterruptibly();
                Iterator<Entry<String, List<QueueData>>> topicTableIt =
                    this.topicQueueTable.entrySet().iterator();
                while (topicTableIt.hasNext()) {
                    Entry<String, List<QueueData>> topicEntry = topicTableIt.next();
                    String topic = topicEntry.getKey();
                    List<QueueData> queueDatas = topicEntry.getValue();
                    if (queueDatas != null && queueDatas.size() > 0
                        && !TopicSysFlag.hasUnitFlag(queueDatas.get(0).getTopicSynFlag())
                        && TopicSysFlag.hasUnitSubFlag(queueDatas.get(0).getTopicSynFlag())) {
                        topicList.getTopicList().add(topic);
                    }
                }
            } finally {
                this.lock.readLock().unlock();
            }
        } catch (Exception e) {
            log.error("getAllTopicList Exception", e);
        }

        return topicList.encode();
    }
}

// 保存broker下某个节点的信息
class BrokerLiveInfo {
    private long lastUpdateTimestamp;
    private DataVersion dataVersion;
    private Channel channel;
    private String haServerAddr;

    public BrokerLiveInfo(long lastUpdateTimestamp, DataVersion dataVersion, Channel channel,
        String haServerAddr) {
        this.lastUpdateTimestamp = lastUpdateTimestamp;
        this.dataVersion = dataVersion;
        this.channel = channel;
        this.haServerAddr = haServerAddr;
    }

    public long getLastUpdateTimestamp() {
        return lastUpdateTimestamp;
    }

    public void setLastUpdateTimestamp(long lastUpdateTimestamp) {
        this.lastUpdateTimestamp = lastUpdateTimestamp;
    }

    public DataVersion getDataVersion() {
        return dataVersion;
    }

    public void setDataVersion(DataVersion dataVersion) {
        this.dataVersion = dataVersion;
    }

    public Channel getChannel() {
        return channel;
    }

    public void setChannel(Channel channel) {
        this.channel = channel;
    }

    public String getHaServerAddr() {
        return haServerAddr;
    }

    public void setHaServerAddr(String haServerAddr) {
        this.haServerAddr = haServerAddr;
    }

    @Override
    public String toString() {
        return "BrokerLiveInfo [lastUpdateTimestamp=" + lastUpdateTimestamp + ", dataVersion=" + dataVersion
            + ", channel=" + channel + ", haServerAddr=" + haServerAddr + "]";
    }
}
