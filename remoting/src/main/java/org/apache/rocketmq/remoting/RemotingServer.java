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
package org.apache.rocketmq.remoting;

import io.netty.channel.Channel;
import java.util.concurrent.ExecutorService;
import org.apache.rocketmq.remoting.common.Pair;
import org.apache.rocketmq.remoting.exception.RemotingSendRequestException;
import org.apache.rocketmq.remoting.exception.RemotingTimeoutException;
import org.apache.rocketmq.remoting.exception.RemotingTooMuchRequestException;
import org.apache.rocketmq.remoting.netty.NettyRequestProcessor;
import org.apache.rocketmq.remoting.protocol.RemotingCommand;

public interface RemotingServer extends RemotingService {
    // 注册一个能够处理requestCode对应的请求类型的处理器，处理请求时在executor线程池中执行。RocketMQ中发送的请求都带有请求的code，
    // 也就是这里的requestCode，表示了请求的类型，如pull消息请求的code为11，所有的requestCode都定义在了RequestCode类，如
    // RequestCode.PULL_MESSAGE = 11
    void registerProcessor(final int requestCode, final NettyRequestProcessor processor,
        final ExecutorService executor);

    // 注册默认处理器，当无法根据请求的code找到处理器时使用默认处理器处理
    void registerDefaultProcessor(final NettyRequestProcessor processor, final ExecutorService executor);

    // 返回服务器监听的本地端口
    int localListenPort();

    // registerProcessor方法注册处理器时会同时指定处理器和线程池，所以对于特定类型的requestCode，处理器和线程池的关系是唯一确定的，
    // 这里返回指定的requestCode对应的处理器和线程池
    Pair<NettyRequestProcessor, ExecutorService> getProcessorPair(final int requestCode);

    // 同步发送请求，timeoutMillis时间后没有确认收到响应则超时
    RemotingCommand invokeSync(final Channel channel, final RemotingCommand request,
        final long timeoutMillis) throws InterruptedException, RemotingSendRequestException,
        RemotingTimeoutException;

    // 异步发送请求，timeoutMillis时间后没有确认收到响应则超时
    void invokeAsync(final Channel channel, final RemotingCommand request, final long timeoutMillis,
        final InvokeCallback invokeCallback) throws InterruptedException,
        RemotingTooMuchRequestException, RemotingTimeoutException, RemotingSendRequestException;

    // 以oneway的方法发送请求，也就是发送请求的调用方不关心是否发送成功，也不关心是否有响应，timeoutMillis表示在该时间后没有执行发送操作
    // 则超时，注意这里只针对是否执行了发送操作，而不是是否发送成功
    void invokeOneway(final Channel channel, final RemotingCommand request, final long timeoutMillis)
        throws InterruptedException, RemotingTooMuchRequestException, RemotingTimeoutException,
        RemotingSendRequestException;

}
