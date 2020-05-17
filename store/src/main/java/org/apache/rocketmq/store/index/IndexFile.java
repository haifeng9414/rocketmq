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
package org.apache.rocketmq.store.index;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.util.List;
import org.apache.rocketmq.common.constant.LoggerName;
import org.apache.rocketmq.logging.InternalLogger;
import org.apache.rocketmq.logging.InternalLoggerFactory;
import org.apache.rocketmq.store.MappedFile;

public class IndexFile {
    private static final InternalLogger log = InternalLoggerFactory.getLogger(LoggerName.STORE_LOGGER_NAME);
    private static int hashSlotSize = 4;
    private static int indexSize = 20;
    private static int invalidIndex = 0;
    private final int hashSlotNum;
    private final int indexNum;
    private final MappedFile mappedFile;
    private final FileChannel fileChannel;
    private final MappedByteBuffer mappedByteBuffer;
    private final IndexHeader indexHeader;

    public IndexFile(final String fileName, final int hashSlotNum, final int indexNum,
        final long endPhyOffset, final long endTimestamp) throws IOException {
        int fileTotalSize =
            IndexHeader.INDEX_HEADER_SIZE + (hashSlotNum * hashSlotSize) + (indexNum * indexSize);
        this.mappedFile = new MappedFile(fileName, fileTotalSize);
        this.fileChannel = this.mappedFile.getFileChannel();
        this.mappedByteBuffer = this.mappedFile.getMappedByteBuffer();
        this.hashSlotNum = hashSlotNum;
        this.indexNum = indexNum;

        ByteBuffer byteBuffer = this.mappedByteBuffer.slice();
        this.indexHeader = new IndexHeader(byteBuffer);

        if (endPhyOffset > 0) {
            this.indexHeader.setBeginPhyOffset(endPhyOffset);
            this.indexHeader.setEndPhyOffset(endPhyOffset);
        }

        if (endTimestamp > 0) {
            this.indexHeader.setBeginTimestamp(endTimestamp);
            this.indexHeader.setEndTimestamp(endTimestamp);
        }
    }

    public String getFileName() {
        return this.mappedFile.getFileName();
    }

    public void load() {
        this.indexHeader.load();
    }

    public void flush() {
        long beginTime = System.currentTimeMillis();
        if (this.mappedFile.hold()) {
            this.indexHeader.updateByteBuffer();
            this.mappedByteBuffer.force();
            this.mappedFile.release();
            log.info("flush index file elapsed time(ms) " + (System.currentTimeMillis() - beginTime));
        }
    }

    public boolean isWriteFull() {
        return this.indexHeader.getIndexCount() >= this.indexNum;
    }

    public boolean destroy(final long intervalForcibly) {
        return this.mappedFile.destroy(intervalForcibly);
    }

    /*
     key为topic#{key}的形式，{key}可以是消息的uniqKey和消费者设置的KEYS属性，phyOffset为消息的位移，storeTimestamp为消息保存到broker的时间

     index文件的结构如下：
     ｜ Header：index文件header，用于保存index文件及其保存的消息相关信息（40B） ｜ Slot Table：hash槽（500W * 4B） ｜ Linked List：用数组表示的链表，用于解决hash冲突（2000W * 20B） ｜

     index文件计算和保存索引的逻辑和HashMap类似，下面分析一个key建立索引的过程。

     假设index文件结构如下：

     ｜ header内容忽略 ｜ [x] [] [5] [] [] | [x] [x] [x] [x] [b] [] [] [] [] [] |

     上面的[]模拟hash槽和数组元素，x表示存在值，具体的值忽略，当为key：a构建索引时，计算过程是，假设a的hashCode为13，则：
     13 % 5 = 3（实际上应该对500W求余，hash槽的数量是500W）
     40 + 3 * 4 = 52（40是header内容的长度，而一个hash槽的长度为4B，所以3 * 4得到a在hash槽中的位置，加上40等于其在index文件中的位移）

     根据上面的计算结果，key：a应该放在hash槽的第3个槽，保存索引时，先取出第3个槽保存的int值，即5，该值为之前保存在该hash槽的消息在用
     数组表示的Linked List中的位置位置，根据5能够找到之前已经在保存过索引的消息b

     找到hash槽并且获取到槽上的数值后，就可以保存消息a的索引了，因为index文件通过数组表示链表，所以消息a的索引会被保存在b后面一个位置，即
     index文件的索引是依次存放的，消息a保存的索引占用20B，内容是：
     消息a的key的hashCode（4B）
     消息a在commitlog文件的位移（8B）
     消息a的创建时间 - index文件保存的索引对应的消息中保存时间的最小值（4B）
     当前hash槽的数值（4B）

     保存消息a之后，更新当前hash槽的数值为当前index文件索引的个数（这个值等于消息a的数组位置），由于消息a的索引中保存了之前hash槽的数值，
     所以当根据key：b查找消息时，先根据b的hashCode找到hash槽3，获取槽的值，此时值应该为6，找到数组的第6个元素（index文件的数组位置从1开始算的）
     得到消息a的索引，发现a和hashCode和当前正在寻找的b的hashCode不一致，继续寻找，获取消息a的索引中保存的hash槽的数值（即最后4B的数据）得到5，
     根据5找到消息b的索引的位置，发现hashCode匹配，返回消息b的消息位移
     */
    public boolean putKey(final String key, final long phyOffset, final long storeTimestamp) {
        // this.indexNum为索引数量的最大值，默认500W * 4
        if (this.indexHeader.getIndexCount() < this.indexNum) {
            // 获取key的hashCode
            int keyHash = indexKeyHashMethod(key);
            // this.hashSlotNum为hash槽的个数，默认500W个，这里获取索引应该位于哪个hash槽
            int slotPos = keyHash % this.hashSlotNum;
            // index文件有固定40字节的header，而每个hash槽4个字节，这里计算当前索引的hash槽在index文件中的位移
            int absSlotPos = IndexHeader.INDEX_HEADER_SIZE + slotPos * hashSlotSize;

            FileLock fileLock = null;

            try {

                // fileLock = this.fileChannel.lock(absSlotPos, hashSlotSize,
                // false);
                // 获取当前槽上的int值，该值表示当前槽保存的索引的位置（数组位置）
                int slotValue = this.mappedByteBuffer.getInt(absSlotPos);
                // invalidIndex默认为0，indexCount默认为1，每增加一个索引，indexCount加1
                // slotValue的值实际上等于该位置hash槽保存的消息在由数组表示的Linked List中的数组位置，这里如果
                // slotValue满足slotValue <= invalidIndex说明该hash槽还没有保存过索引，如果大于indexCount则表示
                // 索引位置不合法，这两种情况都把slotValue设置为invalidIndex，当前即将保存的索引最后会有一个值指向
                // slotValue，相当于HashMap中链表的最后一个节点指向null
                if (slotValue <= invalidIndex || slotValue > this.indexHeader.getIndexCount()) {
                    slotValue = invalidIndex;
                }

                // this.indexHeader.getBeginTimestamp()为index文件保存的消息中创建时间的最小值，这里计算当前消息和当前index文件
                // 保存的消息的最小时间戳之间的差
                long timeDiff = storeTimestamp - this.indexHeader.getBeginTimestamp();

                // 时间戳转换为秒
                timeDiff = timeDiff / 1000;

                if (this.indexHeader.getBeginTimestamp() <= 0) {
                    timeDiff = 0;
                } else if (timeDiff > Integer.MAX_VALUE) {
                    timeDiff = Integer.MAX_VALUE;
                } else if (timeDiff < 0) {
                    timeDiff = 0;
                }

                // 计算索引的位置，结果为header(40B) + hashSlot * 4 + 当前索引数量 * 20，index文件用数组表示链表，用于解决hash
                // 冲突，所以索引是依次保存的，则计算位置时只需要递增indexCount的值即可
                int absIndexPos =
                    IndexHeader.INDEX_HEADER_SIZE + this.hashSlotNum * hashSlotSize
                        + this.indexHeader.getIndexCount() * indexSize;

                // 在索引位置放置hash值，4个字节
                this.mappedByteBuffer.putInt(absIndexPos, keyHash);
                // 继续放置消息的位移，8个字节
                this.mappedByteBuffer.putLong(absIndexPos + 4, phyOffset);
                // 继续放置时间戳的差值，4个字节
                this.mappedByteBuffer.putInt(absIndexPos + 4 + 8, (int) timeDiff);
                // 继续放置当前hash槽上的值，4个字节，这个值等于前一个保存到该hash槽的消息的数组位置，以此实现了用数组表示链表的效果
                this.mappedByteBuffer.putInt(absIndexPos + 4 + 8 + 4, slotValue);

                // 更新当前hash槽的值为当前index文件索引的个数，即当前消息在索引中的位置
                this.mappedByteBuffer.putInt(absSlotPos, this.indexHeader.getIndexCount());

                // indexCount的值从1开始的，这里判断如果当前消息是第一个保存到当前index文件的消息
                if (this.indexHeader.getIndexCount() <= 1) {
                    // 设置当前消息的信息为index文件的起始信息
                    this.indexHeader.setBeginPhyOffset(phyOffset);
                    this.indexHeader.setBeginTimestamp(storeTimestamp);
                }

                // 这里直接增加hashSlotCount不知道啥意思，这样indexCount的值和hashSlotCount的值不就总是差1了吗（indexCount从1
                // 开始，hashSlotCount从0开始），这样hashSlotCount的值为啥还需要保存，没啥意义
                this.indexHeader.incHashSlotCount();
                // 增加索引的个数
                this.indexHeader.incIndexCount();
                // 更新当前消息的信息为当前index文件对应信息的最大值
                this.indexHeader.setEndPhyOffset(phyOffset);
                this.indexHeader.setEndTimestamp(storeTimestamp);

                return true;
            } catch (Exception e) {
                log.error("putKey exception, Key: " + key + " KeyHashCode: " + key.hashCode(), e);
            } finally {
                if (fileLock != null) {
                    try {
                        fileLock.release();
                    } catch (IOException e) {
                        log.error("Failed to release the lock", e);
                    }
                }
            }
        } else {
            log.warn("Over index file capacity: index count = " + this.indexHeader.getIndexCount()
                + "; index max num = " + this.indexNum);
        }

        return false;
    }

    public int indexKeyHashMethod(final String key) {
        int keyHash = key.hashCode();
        int keyHashPositive = Math.abs(keyHash);
        if (keyHashPositive < 0)
            keyHashPositive = 0;
        return keyHashPositive;
    }

    public long getBeginTimestamp() {
        return this.indexHeader.getBeginTimestamp();
    }

    public long getEndTimestamp() {
        return this.indexHeader.getEndTimestamp();
    }

    public long getEndPhyOffset() {
        return this.indexHeader.getEndPhyOffset();
    }

    public boolean isTimeMatched(final long begin, final long end) {
        boolean result = begin < this.indexHeader.getBeginTimestamp() && end > this.indexHeader.getEndTimestamp();
        result = result || (begin >= this.indexHeader.getBeginTimestamp() && begin <= this.indexHeader.getEndTimestamp());
        result = result || (end >= this.indexHeader.getBeginTimestamp() && end <= this.indexHeader.getEndTimestamp());
        return result;
    }

    // 根据key、begin、end为查询条件在索引中查找消息
    public void selectPhyOffset(final List<Long> phyOffsets, final String key, final int maxNum,
        final long begin, final long end, boolean lock) {
        if (this.mappedFile.hold()) {
            // 获取key的hashCode
            int keyHash = indexKeyHashMethod(key);
            // 找到hash槽的位置
            int slotPos = keyHash % this.hashSlotNum;
            // 计算hash槽在index文件的位移
            int absSlotPos = IndexHeader.INDEX_HEADER_SIZE + slotPos * hashSlotSize;

            FileLock fileLock = null;
            try {
                if (lock) {
                    // fileLock = this.fileChannel.lock(absSlotPos,
                    // hashSlotSize, true);
                }

                // 获取当前hash槽上的int值
                int slotValue = this.mappedByteBuffer.getInt(absSlotPos);
                // if (fileLock != null) {
                // fileLock.release();
                // fileLock = null;
                // }

                // 如果满足if条件说明当前hash槽没有保存消息索引
                if (slotValue <= invalidIndex || slotValue > this.indexHeader.getIndexCount()
                    || this.indexHeader.getIndexCount() <= 1) {
                } else {
                    // 遍历当前hash槽对应的链表
                    for (int nextIndexToRead = slotValue; ; ) {
                        // 如果找到的消息数量达到了maxNum，直接返回结果
                        if (phyOffsets.size() >= maxNum) {
                            break;
                        }

                        // 计算消息a的索引在index文件的位移
                        int absIndexPos =
                            IndexHeader.INDEX_HEADER_SIZE + this.hashSlotNum * hashSlotSize
                                + nextIndexToRead * indexSize;

                        // 读取索引信息
                        int keyHashRead = this.mappedByteBuffer.getInt(absIndexPos);
                        long phyOffsetRead = this.mappedByteBuffer.getLong(absIndexPos + 4);

                        long timeDiff = (long) this.mappedByteBuffer.getInt(absIndexPos + 4 + 8);
                        // prevIndexRead的值是链表下一个节点在索引数组中的位置
                        int prevIndexRead = this.mappedByteBuffer.getInt(absIndexPos + 4 + 8 + 4);

                        if (timeDiff < 0) {
                            break;
                        }

                        timeDiff *= 1000L;

                        // 计算消息的保存时间
                        long timeRead = this.indexHeader.getBeginTimestamp() + timeDiff;
                        boolean timeMatched = (timeRead >= begin) && (timeRead <= end);

                        // 如果正在找的key的hashCode和当前索引的hashCode相等，并且索引的消息的保存时间在指定范围内，则保存消息的
                        // 位移到结果集
                        // 需要注意的是，这里只是判断hashCode是否相等，不同的消息的key的hashCode是可能相同的，所以消费者在根据索引查询到
                        // 消息后，还得根据key的实际内容进行过滤
                        if (keyHash == keyHashRead && timeMatched) {
                            phyOffsets.add(phyOffsetRead);
                        }

                        // 满足条件说明链表到头了，或者当timeRead < begin时，表示当前索引的消息的保存时间小于正在寻找的时间范围的起始
                        // 值，此时没必要再寻找了，链表后面的索引肯定都timeRead < begin，因为index文件中链表节点越接近链表头，消息保存时间越大
                        if (prevIndexRead <= invalidIndex
                            || prevIndexRead > this.indexHeader.getIndexCount()
                            || prevIndexRead == nextIndexToRead || timeRead < begin) {
                            break;
                        }

                        // 继续寻找下一个链表节点
                        nextIndexToRead = prevIndexRead;
                    }
                }
            } catch (Exception e) {
                log.error("selectPhyOffset exception ", e);
            } finally {
                if (fileLock != null) {
                    try {
                        fileLock.release();
                    } catch (IOException e) {
                        log.error("Failed to release the lock", e);
                    }
                }

                this.mappedFile.release();
            }
        }
    }
}
