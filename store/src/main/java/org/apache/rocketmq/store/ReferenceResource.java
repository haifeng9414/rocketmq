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
package org.apache.rocketmq.store;

import java.util.concurrent.atomic.AtomicLong;

public abstract class ReferenceResource {
    // refCount表示被hold的次数
    protected final AtomicLong refCount = new AtomicLong(1);
    protected volatile boolean available = true;
    protected volatile boolean cleanupOver = false;
    private volatile long firstShutdownTimestamp = 0;

    public synchronized boolean hold() {
        if (this.isAvailable()) {
            if (this.refCount.getAndIncrement() > 0) {
                return true;
            } else {
                this.refCount.getAndDecrement();
            }
        }

        return false;
    }

    public boolean isAvailable() {
        return this.available;
    }

    public void shutdown(final long intervalForcibly) {
        // 如果是available状态的shutdown，只是更新状态
        if (this.available) {
            this.available = false;
            this.firstShutdownTimestamp = System.currentTimeMillis();
            this.release();
        } else if (this.getRefCount() > 0) {
            // 否则如果再次调用shutdown方法，available为false，此时判断上次shutdown的时间距离现在是不是超过了intervalForcibly的值
            if ((System.currentTimeMillis() - this.firstShutdownTimestamp) >= intervalForcibly) {
                // 满足条件则将refCount清空并且再-1000保证refCount为负数
                this.refCount.set(-1000 - this.getRefCount());
                // 通过release调用cleanup回调
                this.release();
            }
        }
    }

    public void release() {
        // 释放引用次数
        long value = this.refCount.decrementAndGet();
        if (value > 0)
            return;

        synchronized (this) {
            // 如果引用次数减到不大于0则调用cleanup回调
            this.cleanupOver = this.cleanup(value);
        }
    }

    public long getRefCount() {
        return this.refCount.get();
    }

    public abstract boolean cleanup(final long currentRef);

    public boolean isCleanupOver() {
        return this.refCount.get() <= 0 && this.cleanupOver;
    }
}
