`NettyRemotingAbstract`抽象类提供了发送请求、接收请求或响应的逻辑，下面分析`NettyRemotingAbstract`抽象类提供的方法的作用和实现。

在分析实现之前，先放下`NettyRemotingAbstract`类的源码，源码中已经包含了分析注释，在源码下面会详细解释`NettyRemotingAbstract`类的实现。

## `NettyRemotingAbstract`类源码：
```java
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
package org.apache.rocketmq.remoting.netty;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslHandler;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.apache.rocketmq.logging.InternalLogger;
import org.apache.rocketmq.logging.InternalLoggerFactory;
import org.apache.rocketmq.remoting.ChannelEventListener;
import org.apache.rocketmq.remoting.InvokeCallback;
import org.apache.rocketmq.remoting.RPCHook;
import org.apache.rocketmq.remoting.common.Pair;
import org.apache.rocketmq.remoting.common.RemotingHelper;
import org.apache.rocketmq.remoting.common.SemaphoreReleaseOnlyOnce;
import org.apache.rocketmq.remoting.common.ServiceThread;
import org.apache.rocketmq.remoting.exception.RemotingSendRequestException;
import org.apache.rocketmq.remoting.exception.RemotingTimeoutException;
import org.apache.rocketmq.remoting.exception.RemotingTooMuchRequestException;
import org.apache.rocketmq.remoting.protocol.RemotingCommand;
import org.apache.rocketmq.remoting.protocol.RemotingSysResponseCode;

public abstract class NettyRemotingAbstract {

    /**
     * Remoting logger instance.
     */
    private static final InternalLogger log = InternalLoggerFactory.getLogger(RemotingHelper.ROCKETMQ_REMOTING);

    /**
     * Semaphore to limit maximum number of on-going one-way requests, which protects system memory footprint.
     */
    // oneway类型请求的信号量，目的是防止客户端无限制发送请求
    protected final Semaphore semaphoreOneway;

    /**
     * Semaphore to limit maximum number of on-going asynchronous requests, which protects system memory footprint.
     */
    // 异步请求的信号量，目的是防止客户端无限制发送请求，对于同步请求，因为是同步的，所以没有必要限制发送，也就没有对应的信号量
    protected final Semaphore semaphoreAsync;

    /**
     * This map caches all on-going requests.
     */
    protected final ConcurrentMap<Integer /* opaque */, ResponseFuture> responseTable =
        new ConcurrentHashMap<Integer, ResponseFuture>(256);

    /**
     * This container holds all processors per request code, aka, for each incoming request, we may look up the
     * responding processor in this map to handle the request.
     */
    // 保存能够处理收到的请求的NettyRequestProcessor，由子类负责维护processorTable
    protected final HashMap<Integer/* request code */, Pair<NettyRequestProcessor, ExecutorService>> processorTable =
        new HashMap<Integer, Pair<NettyRequestProcessor, ExecutorService>>(64);

    /**
     * Executor to feed netty events to user defined {@link ChannelEventListener}.
     */
    // 负责通知ChannelEventListener其监听的事件发生的线程
    protected final NettyEventExecutor nettyEventExecutor = new NettyEventExecutor();

    /**
     * The default request processor to use in case there is no exact match in {@link #processorTable} per request code.
     */
    // 默认的请求处理器，当某个请求无法从processorTable获取对应的NettyRequestProcessor时使用该处理器处理请求
    protected Pair<NettyRequestProcessor, ExecutorService> defaultRequestProcessor;

    /**
     * SSL context via which to create {@link SslHandler}.
     */
    protected volatile SslContext sslContext;

    /**
     * custom rpc hooks
     */
    // 服务器收到请求并处理请求前和处理请求后会分别调用RPCHook的回调方法，对于NettyRemotingAbstract的实现类NettyRemotingClient，
    // 其在发送请求前和发送请求后也会分别调用RPCHook的回调方法
    protected List<RPCHook> rpcHooks = new ArrayList<RPCHook>();

    static {
        NettyLogger.initNettyLogger();
    }

    /**
     * Constructor, specifying capacity of one-way and asynchronous semaphores.
     *
     * @param permitsOneway Number of permits for one-way requests.
     * @param permitsAsync Number of permits for asynchronous requests.
     */
    public NettyRemotingAbstract(final int permitsOneway, final int permitsAsync) {
        // 初始化信号量
        this.semaphoreOneway = new Semaphore(permitsOneway, true);
        this.semaphoreAsync = new Semaphore(permitsAsync, true);
    }

    /**
     * Custom channel event listener.
     *
     * @return custom channel event listener if defined; null otherwise.
     */
    // ChannelEventListener会监听channel的connect、close、exception和idle事件
    public abstract ChannelEventListener getChannelEventListener();

    /**
     * Put a netty event to the executor.
     *
     * @param event Netty event instance.
     */
    public void putNettyEvent(final NettyEvent event) {
        // nettyEventExecutor负责将NettyEvent发送给ChannelEventListener
        this.nettyEventExecutor.putNettyEvent(event);
    }

    /**
     * Entry of incoming command processing.
     *
     * <p>
     * <strong>Note:</strong>
     * The incoming remoting command may be
     * <ul>
     * <li>An inquiry request from a remote peer component;</li>
     * <li>A response to a previous request issued by this very participant.</li>
     * </ul>
     * </p>
     *
     * @param ctx Channel handler context.
     * @param msg incoming remoting command.
     * @throws Exception if there were any error while processing the incoming command.
     */
    // 处理request，供子类使用，子类负责根据netty接收到的数据构建RemotingCommand对象
    public void processMessageReceived(ChannelHandlerContext ctx, RemotingCommand msg) throws Exception {
        final RemotingCommand cmd = msg;
        if (cmd != null) {
            // 根据RemotingCommand类型处理RemotingCommand对象，RemotingCommand包含了完整的请求或响应数据和信息
            switch (cmd.getType()) {
                case REQUEST_COMMAND:
                    processRequestCommand(ctx, cmd);
                    break;
                case RESPONSE_COMMAND:
                    processResponseCommand(ctx, cmd);
                    break;
                default:
                    break;
            }
        }
    }

    protected void doBeforeRpcHooks(String addr, RemotingCommand request) {
        if (rpcHooks.size() > 0) {
            for (RPCHook rpcHook: rpcHooks) {
                rpcHook.doBeforeRequest(addr, request);
            }
        }
    }

    protected void doAfterRpcHooks(String addr, RemotingCommand request, RemotingCommand response) {
        if (rpcHooks.size() > 0) {
            for (RPCHook rpcHook: rpcHooks) {
                rpcHook.doAfterResponse(addr, request, response);
            }
        }
    }


    /**
     * Process incoming request command issued by remote peer.
     *
     * @param ctx channel handler context.
     * @param cmd request command.
     */
    // 处理收到的请求
    public void processRequestCommand(final ChannelHandlerContext ctx, final RemotingCommand cmd) {
        // processorTable以code为key，Pair<NettyRequestProcessor, ExecutorService>为value保存能够处理RemotingCommand的
        // 处理器，即NettyRequestProcessor，NettyRequestProcessor用于处理收到的请求，ExecutorService是执行处理过程的线程池。
        // processorTable由子类负责添加数据，这里根据RemotingCommand的code获取对应的NettyRequestProcessor
        final Pair<NettyRequestProcessor, ExecutorService> matched = this.processorTable.get(cmd.getCode());
        // 如果未找到NettyRequestProcessor则使用默认的NettyRequestProcessor
        final Pair<NettyRequestProcessor, ExecutorService> pair = null == matched ? this.defaultRequestProcessor : matched;
        // opaque为请求的id，每个请求的opaque都不同，响应的opaque等于请求的opaque
        final int opaque = cmd.getOpaque();

        if (pair != null) {
            // 请求的处理是在线程池中做的，这里创建请求处理的线程任务
            Runnable run = new Runnable() {
                @Override
                public void run() {
                    try {
                        // 处理请求前调用RPCHook的doBeforeRequest方法
                        doBeforeRpcHooks(RemotingHelper.parseChannelRemoteAddr(ctx.channel()), cmd);
                        // object1就是NettyRequestProcessor
                        final RemotingCommand response = pair.getObject1().processRequest(ctx, cmd);
                        // 处理请求后调用RPCHook的doAfterResponse方法
                        doAfterRpcHooks(RemotingHelper.parseChannelRemoteAddr(ctx.channel()), cmd, response);

                        // oneway的调用方法对response不关心，所以这里只对非oneway的请求做response处理
                        if (!cmd.isOnewayRPC()) {
                            if (response != null) {
                                // 请求和响应的id是一样的
                                response.setOpaque(opaque);
                                // 设置response的flag属性，将其标记为response类型
                                response.markResponseType();
                                try {
                                    // 处理请求结束后发送响应
                                    ctx.writeAndFlush(response);
                                } catch (Throwable e) {
                                    log.error("process request over, but response failed", e);
                                    log.error(cmd.toString());
                                    log.error(response.toString());
                                }
                            } else {

                            }
                        }
                    } catch (Throwable e) {
                        log.error("process request exception", e);
                        log.error(cmd.toString());

                        if (!cmd.isOnewayRPC()) {
                            // 发送异常时返回异常信息
                            final RemotingCommand response = RemotingCommand.createResponseCommand(RemotingSysResponseCode.SYSTEM_ERROR,
                                RemotingHelper.exceptionSimpleDesc(e));
                            response.setOpaque(opaque);
                            ctx.writeAndFlush(response);
                        }
                    }
                }
            };

            // 判断是否拒绝请求，可以用于控制请求速率
            if (pair.getObject1().rejectRequest()) {
                // 返回系统繁忙
                final RemotingCommand response = RemotingCommand.createResponseCommand(RemotingSysResponseCode.SYSTEM_BUSY,
                    "[REJECTREQUEST]system busy, start flow control for a while");
                response.setOpaque(opaque);
                ctx.writeAndFlush(response);
                return;
            }

            try {
                // RequestTask对象封装了处理请求的线程任务、Channel和RemotingCommand对象。这里用RequestTask对象再封装一次请求处理
                // 的线程任务是因为RequestTask支持stop，RequestTask的run方法在执行真正的线程任务前会判断自己是否被标记为stop了，线程
                // 池的逻辑是在线程数到达其coreSize后将任务放到BlockingQueue中，直到BlockingQueue满了才继续创建线程直到线程数到达
                // maximumPoolSize，所以BlockingQueue中保存了已被提交但是未被运行的任务。这里提交的是RequestTask对象，所以线程池的
                // BlockingQueue中保存的也是RequestTask对象，这样就能够在必要的时候通过BlockingQueue获取等待执行的RequestTask对象
                // 并将其stop
                final RequestTask requestTask = new RequestTask(run, ctx.channel(), cmd);
                pair.getObject2().submit(requestTask);
            } catch (RejectedExecutionException e) {
                // RejectedExecutionException是在线程池队列满了且线程数量到达了maximumPoolSize再提交任务时发生
                if ((System.currentTimeMillis() % 10000) == 0) {
                    log.warn(RemotingHelper.parseChannelRemoteAddr(ctx.channel())
                        + ", too many requests and system thread pool busy, RejectedExecutionException "
                        + pair.getObject2().toString()
                        + " request code: " + cmd.getCode());
                }

                // 此时返回系统繁忙
                if (!cmd.isOnewayRPC()) {
                    final RemotingCommand response = RemotingCommand.createResponseCommand(RemotingSysResponseCode.SYSTEM_BUSY,
                        "[OVERLOAD]system busy, start flow control for a while");
                    response.setOpaque(opaque);
                    ctx.writeAndFlush(response);
                }
            }
        } else {
            // 如果没有找到NettyRequestProcessor则返回error
            String error = " request type " + cmd.getCode() + " not supported";
            final RemotingCommand response =
                RemotingCommand.createResponseCommand(RemotingSysResponseCode.REQUEST_CODE_NOT_SUPPORTED, error);
            // 请求和响应的id是一样的
            response.setOpaque(opaque);
            ctx.writeAndFlush(response);
            log.error(RemotingHelper.parseChannelRemoteAddr(ctx.channel()) + error);
        }
    }

    /**
     * Process response from remote peer to the previous issued requests.
     *
     * @param ctx channel handler context.
     * @param cmd response command instance.
     */
    // 处理收到的响应
    public void processResponseCommand(ChannelHandlerContext ctx, RemotingCommand cmd) {
        // 获取响应对应的请求的id
        final int opaque = cmd.getOpaque();
        // processResponseCommand方法的逻辑结合invokeSyncImpl方法的实现就能够理解，可以先看invokeSyncImpl方法的实现再回到这里。
        // 这里根据发送出去的请求的id获取对应的responseFuture
        final ResponseFuture responseFuture = responseTable.get(opaque);
        if (responseFuture != null) {
            // cmd就是响应的内容和相关信息
            responseFuture.setResponseCommand(cmd);

            responseTable.remove(opaque);

            // 如果存在invokeCallback则为异步调用，这里调用executeInvokeCallback方法通知异步调用方操作执行完成，对于异步调用，
            // 不需要执行else语句中的方法。
            if (responseFuture.getInvokeCallback() != null) {
                executeInvokeCallback(responseFuture);
            } else {
                // 否则是同步调用，这里调用putResponse使得invokeSyncImpl方法停止阻塞
                responseFuture.putResponse(cmd);
                // 释放当前请求占用的信号量
                responseFuture.release();
            }
        } else {
            // oneway类型的请求在responseTable中不存在记录
            log.warn("receive response, but not matched any request, " + RemotingHelper.parseChannelRemoteAddr(ctx.channel()));
            log.warn(cmd.toString());
        }
    }

    /**
     * Execute callback in callback executor. If callback executor is null, run directly in current thread
     */
    private void executeInvokeCallback(final ResponseFuture responseFuture) {
        boolean runInThisThread = false;
        // 由子类实现，返回专门用于执行executeInvokeCallback的线程池
        ExecutorService executor = this.getCallbackExecutor();
        if (executor != null) {
            try {
                executor.submit(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            responseFuture.executeInvokeCallback();
                        } catch (Throwable e) {
                            log.warn("execute callback in executor exception, and callback throw", e);
                        } finally {
                            responseFuture.release();
                        }
                    }
                });
            } catch (Exception e) {
                runInThisThread = true;
                log.warn("execute callback in executor exception, maybe executor busy", e);
            }
        } else {
            runInThisThread = true;
        }

        // 如果不存在线程池或线程池满了则用当前线程执行executeInvokeCallback
        if (runInThisThread) {
            try {
                responseFuture.executeInvokeCallback();
            } catch (Throwable e) {
                log.warn("executeInvokeCallback Exception", e);
            } finally {
                responseFuture.release();
            }
        }
    }



    /**
     * Custom RPC hook.
     * Just be compatible with the previous version, use getRPCHooks instead.
     */
    @Deprecated
    protected RPCHook getRPCHook() {
        if (rpcHooks.size() > 0) {
            return rpcHooks.get(0);
        }
        return null;
    }

    /**
     * Custom RPC hooks.
     *
     * @return RPC hooks if specified; null otherwise.
     */
    public List<RPCHook> getRPCHooks() {
        return rpcHooks;
    }


    /**
     * This method specifies thread pool to use while invoking callback methods.
     *
     * @return Dedicated thread pool instance if specified; or null if the callback is supposed to be executed in the
     * netty event-loop thread.
     */
    public abstract ExecutorService getCallbackExecutor();

    /**
     * <p>
     * This method is periodically invoked to scan and expire deprecated request.
     * </p>
     */
    public void scanResponseTable() {
        final List<ResponseFuture> rfList = new LinkedList<ResponseFuture>();
        // responseTable保存了请求对应的ResponseFuture对象（除了oneway类型的请求），这里检查这些ResponseFuture是否超时（在timeout
        // 基础上再加上1秒作为超时时间）。所有超时的ResponseFuture都保存到rfList在最后分别调用executeInvokeCallback方法，该方法会执
        // 行ResponseFuture的invokeCallback方法通知调用方操作完成了。ResponseFuture的实现保证了对于一个ResponseFuture多次调用
        // executeInvokeCallback是幂等的。针对scanResponseTable的这种超时机制，ResponseFuture的invokeCallback的实现，也就是调用
        // 方传入的invokeCallback中需要判断异步操作完成时是否超时
        Iterator<Entry<Integer, ResponseFuture>> it = this.responseTable.entrySet().iterator();
        while (it.hasNext()) {
            Entry<Integer, ResponseFuture> next = it.next();
            ResponseFuture rep = next.getValue();

            // beginTimestamp默认等于ResponseFuture对象的创建时间。当前遍历的Iterator中即包含同步请求的ResponseFuture对象，也包含
            // 异步请求的ResponseFuture对象。但是同步请求理论上是不会满足下面的条件的，因为同步请求通过ResponseFuture对象的
            // waitResponse方法，最多等待timeoutMillis的时间就返回了，这里在timeout的基础上加了1秒，所以通过请求肯定已经收到响应或
            // 超时了。
            /// todo: 上面是一种这里需要加1秒的解释，还有没有其他原因？如果只是上面的原因，1秒也太久了吧。
            if ((rep.getBeginTimestamp() + rep.getTimeoutMillis() + 1000) <= System.currentTimeMillis()) {
                // 每个ResponseFuture都有一个只能调用一次release方法的Semaphore，该Semaphore在创建ResponseFuture时传入，可以用于
                // 控制ResponseFuture的数量。这里将超时的ResponseFuture释放以许可一个新的ResponseFuture
                rep.release();
                it.remove();
                rfList.add(rep);
                log.warn("remove timeout request, " + rep);
            }
        }

        // 依次调用超时的ResponseFuture的executeInvokeCallback方法通知调用方异步操作完成
        for (ResponseFuture rf : rfList) {
            try {
                executeInvokeCallback(rf);
            } catch (Throwable e) {
                log.warn("scanResponseTable, operationComplete Exception", e);
            }
        }
    }

    // 同步发送请求
    public RemotingCommand invokeSyncImpl(final Channel channel, final RemotingCommand request,
        final long timeoutMillis)
        throws InterruptedException, RemotingSendRequestException, RemotingTimeoutException {
        // 获取请求id
        final int opaque = request.getOpaque();

        try {
            // 同步发送就不需要invokeCallback了
            final ResponseFuture responseFuture = new ResponseFuture(channel, opaque, timeoutMillis, null, null);
            // 发送的请求和请求的响应的opaque也就是请求id是相等的，这里保存请求id和responseFuture的映射关系，待收到响应时能够根据
            // 响应的code也就是opaque获取到这里的responseFuture
            this.responseTable.put(opaque, responseFuture);
            final SocketAddress addr = channel.remoteAddress();
            // writeAndFlush是netty的异步发送数据的方法，这里添加个listener监听操作完成
            channel.writeAndFlush(request).addListener(new ChannelFutureListener() {
                @Override
                public void operationComplete(ChannelFuture f) throws Exception {
                    if (f.isSuccess()) {
                        // 标记操作成功，这里只是标记成功而没有做其他操作是因为，发送的请求必须对应着收到一个响应才算成功，看下面的
                        // responseFuture.waitResponse(timeoutMillis)可以发现，invokeSyncImpl方法会阻塞直到putResponse
                        // 方法被调用。对于发送成功的请求，只是标记sendRequestOK为true，invokeSyncImpl会阻塞在waitResponse方
                        // 法，直到processResponseCommand方法收到响应后根据响应的code从responseTable中找到responseFuture并
                        // 调用putResponse方法使得waitResponse方法停止阻塞
                        responseFuture.setSendRequestOK(true);
                        return;
                    } else {
                        // 标记操作失败
                        responseFuture.setSendRequestOK(false);
                    }

                    // 操作失败时执行下面的语句
                    responseTable.remove(opaque);
                    // 如果操作失败则f.cause()就是失败原因
                    responseFuture.setCause(f.cause());
                    // 发送失败的话是不会有response的，所以这里put一个null，同时putResponse方法会调用ResponseFuture成员变量
                    // countDownLatch的countDown方法，使得下面的responseFuture.waitResponse停止等待
                    responseFuture.putResponse(null);
                    log.warn("send a request command to channel <" + addr + "> failed.");
                }
            });

            // 调用ResponseFuture成员变量countDownLatch的await方法，等待指定的超时时间，或者等到putResponse方法被调用
            RemotingCommand responseCommand = responseFuture.waitResponse(timeoutMillis);
            if (null == responseCommand) {
                // 发送请求成功的条件除了sendRequestOK为true，还要responseCommand不为空，当sendRequestOK为true而
                // responseCommand为空说明请求发送出去了但是没有收到响应，此时上面的waitResponse方法因为putResponse
                // 没有被调用而等待了timeoutMillis的时间，所以此时直接返回timeout
                if (responseFuture.isSendRequestOK()) {
                    throw new RemotingTimeoutException(RemotingHelper.parseSocketAddressAddr(addr), timeoutMillis,
                        responseFuture.getCause());
                } else {
                    // sendRequestOK为false表示请求发送失败了
                    throw new RemotingSendRequestException(RemotingHelper.parseSocketAddressAddr(addr), responseFuture.getCause());
                }
            }

            return responseCommand;
        } finally {
            this.responseTable.remove(opaque);
        }
    }

    // 异步发送请求
    public void invokeAsyncImpl(final Channel channel, final RemotingCommand request, final long timeoutMillis,
        final InvokeCallback invokeCallback)
        throws InterruptedException, RemotingTooMuchRequestException, RemotingTimeoutException, RemotingSendRequestException {
        // 记录开始时间
        long beginStartTime = System.currentTimeMillis();
        // 记录请求id
        final int opaque = request.getOpaque();
        // 获取许可证
        boolean acquired = this.semaphoreAsync.tryAcquire(timeoutMillis, TimeUnit.MILLISECONDS);
        if (acquired) {
            // once确保传入的semaphoreAsync的release只会被调用一次
            final SemaphoreReleaseOnlyOnce once = new SemaphoreReleaseOnlyOnce(this.semaphoreAsync);
            // 计算获取许可花费的时间
            long costTime = System.currentTimeMillis() - beginStartTime;
            if (timeoutMillis < costTime) {
                // 超时则释放资源并抛出timeout
                once.release();
                throw new RemotingTimeoutException("invokeAsyncImpl call timeout");
            }

            // timeout需要减去获取许可花费的时间，完成请求发送并收到响应或等待响应超时时会通过invokeCallback通知调用方
            // 可以发现异步发送请求成功后等待响应的超时通知调用方依赖scanResponseTable方法，所以异步发送请求时设置的超
            // 时时间不一定准确，依赖scanResponseTable方法的调用频率
            /// todo: 这样感觉timeout可能会和实际timeout的时间有很大出路，可能是我疏忽了啥关键点，看源码实现我是认为会有很大出路的。
            final ResponseFuture responseFuture = new ResponseFuture(channel, opaque, timeoutMillis - costTime, invokeCallback, once);
            this.responseTable.put(opaque, responseFuture);
            try {
                // 发送请求并添加listener
                channel.writeAndFlush(request).addListener(new ChannelFutureListener() {
                    @Override
                    public void operationComplete(ChannelFuture f) throws Exception {
                        if (f.isSuccess()) {
                            // 发送成功只需要设置sendRequestOK为true，在获取到响应后由processResponseCommand方法通知调用方操作
                            // 结束
                            responseFuture.setSendRequestOK(true);
                            return;
                        }
                        // 发送失败则设置sendRequestOK为false并调用invokeCallback通知调用方操作结束
                        requestFail(opaque);
                        log.warn("send a request command to channel <{}> failed.", RemotingHelper.parseChannelRemoteAddr(channel));
                    }
                });
            } catch (Exception e) {
                // 释放信号量
                responseFuture.release();
                log.warn("send a request command to channel <" + RemotingHelper.parseChannelRemoteAddr(channel) + "> Exception", e);
                throw new RemotingSendRequestException(RemotingHelper.parseChannelRemoteAddr(channel), e);
            }
        } else {
            // 调用invokeAsyncImpl方法的地方对timeoutMillis都做了验证，不确定什么情况下会导致timeoutMillis <= 0
            if (timeoutMillis <= 0) {
                throw new RemotingTooMuchRequestException("invokeAsyncImpl invoke too fast");
            } else {
                // 通过异常告知调用方许可证数量不足
                String info =
                    String.format("invokeAsyncImpl tryAcquire semaphore timeout, %dms, waiting thread nums: %d semaphoreAsyncValue: %d",
                        timeoutMillis,
                        this.semaphoreAsync.getQueueLength(),
                        this.semaphoreAsync.availablePermits()
                    );
                log.warn(info);
                throw new RemotingTimeoutException(info);
            }
        }
    }

    private void requestFail(final int opaque) {
        // 获取发送请求失败的请求对应的responseFuture
        ResponseFuture responseFuture = responseTable.remove(opaque);
        if (responseFuture != null) {
            responseFuture.setSendRequestOK(false);
            // 同步发送请求时需要依赖该方法停止等待
            responseFuture.putResponse(null);
            try {
                // 异步请求通知调用方操作结束
                executeInvokeCallback(responseFuture);
            } catch (Throwable e) {
                log.warn("execute callback in requestFail, and callback throw", e);
            } finally {
                // 释放信号量
                responseFuture.release();
            }
        }
    }

    /**
     * mark the request of the specified channel as fail and to invoke fail callback immediately
     * @param channel the channel which is close already
     */
    // 立即标记channel对应的请求发送失败并通知调用方
    protected void failFast(final Channel channel) {
        Iterator<Entry<Integer, ResponseFuture>> it = responseTable.entrySet().iterator();
        while (it.hasNext()) {
            Entry<Integer, ResponseFuture> entry = it.next();
            // 这就是为什么ResponseFuture类有processChannel属性，通过该属性，能够用一个channel定位到其请求对应的ResponseFuture
            if (entry.getValue().getProcessChannel() == channel) {
                Integer opaque = entry.getKey();
                if (opaque != null) {
                    requestFail(opaque);
                }
            }
        }
    }

    // 只发送请求，不关心响应，所以没有同步等待，也没有invokeCallback，看下面的处理过程可以发现，调用方使用invokeOnewayImpl
    // 方法是不能确定请求是否发送成功或是否收到响应的
    public void invokeOnewayImpl(final Channel channel, final RemotingCommand request, final long s)
        throws InterruptedException, RemotingTooMuchRequestException, RemotingTimeoutException, RemotingSendRequestException {
        // 设置request的flag属性标记该请求是onewayRPC
        request.markOnewayRPC();
        // 获取许可
        boolean acquired = this.semaphoreOneway.tryAcquire(timeoutMillis, TimeUnit.MILLISECONDS);
        if (acquired) {
            final SemaphoreReleaseOnlyOnce once = new SemaphoreReleaseOnlyOnce(this.semaphoreOneway);
            try {
                channel.writeAndFlush(request).addListener(new ChannelFutureListener() {
                    @Override
                    public void operationComplete(ChannelFuture f) throws Exception {
                        // 对于oneway，没有创建request对应的ResponseFuture对象，所以processResponseCommand方法不会处理
                        // oneway请求对应的响应，所以需要在这里释放信号量
                        once.release();
                        if (!f.isSuccess()) {
                            // 由于不关心响应，所以发送失败只需要记录日志，所以调用方使用invokeOnewayImpl方法是不能确定请求是否发
                            // 送成功或是否收到响应
                            log.warn("send a request command to channel <" + channel.remoteAddress() + "> failed.");
                        }
                    }
                });
            } catch (Exception e) {
                once.release();
                log.warn("write send a request command to channel <" + channel.remoteAddress() + "> failed.");
                throw new RemotingSendRequestException(RemotingHelper.parseChannelRemoteAddr(channel), e);
            }
        } else {
            // 和invokeAsyncImpl的处理过程一样
            if (timeoutMillis <= 0) {
                throw new RemotingTooMuchRequestException("invokeOnewayImpl invoke too fast");
            } else {
                String info = String.format(
                    "invokeOnewayImpl tryAcquire semaphore timeout, %dms, waiting thread nums: %d semaphoreAsyncValue: %d",
                    timeoutMillis,
                    this.semaphoreOneway.getQueueLength(),
                    this.semaphoreOneway.availablePermits()
                );
                log.warn(info);
                throw new RemotingTimeoutException(info);
            }
        }
    }

    // 除非被stop，否则不断接收putNettyEvent方法传入的NettyEvent并通知ChannelEventListener对应的事件
    class NettyEventExecutor extends ServiceThread {
        private final LinkedBlockingQueue<NettyEvent> eventQueue = new LinkedBlockingQueue<NettyEvent>();
        private final int maxSize = 10000;

        public void putNettyEvent(final NettyEvent event) {
            if (this.eventQueue.size() <= maxSize) {
                this.eventQueue.add(event);
            } else {
                log.warn("event queue size[{}] enough, so drop this event {}", this.eventQueue.size(), event.toString());
            }
        }

        @Override
        public void run() {
            log.info(this.getServiceName() + " service started");

            final ChannelEventListener listener = NettyRemotingAbstract.this.getChannelEventListener();

            while (!this.isStopped()) {
                try {
                    NettyEvent event = this.eventQueue.poll(3000, TimeUnit.MILLISECONDS);
                    if (event != null && listener != null) {
                        switch (event.getType()) {
                            case IDLE:
                                listener.onChannelIdle(event.getRemoteAddr(), event.getChannel());
                                break;
                            case CLOSE:
                                listener.onChannelClose(event.getRemoteAddr(), event.getChannel());
                                break;
                            case CONNECT:
                                listener.onChannelConnect(event.getRemoteAddr(), event.getChannel());
                                break;
                            case EXCEPTION:
                                listener.onChannelException(event.getRemoteAddr(), event.getChannel());
                                break;
                            default:
                                break;

                        }
                    }
                } catch (Exception e) {
                    log.warn(this.getServiceName() + " service has exception. ", e);
                }
            }

            log.info(this.getServiceName() + " service end");
        }

        @Override
        public String getServiceName() {
            return NettyEventExecutor.class.getSimpleName();
        }
    }
}

```

## 实现
`NettyRemotingAbstract`类的方法之间是有联系的，下面根据功能点对`NettyRemotingAbstract`的实现进行分析。

### 作为服务器接收网络请求
`NettyRemotingAbstract`类处理请求的方法是`processRequestCommand()`，相对应的处理响应的方法是`processResponseCommand()`。`NettyRemotingAbstract`类还提供了`processMessageReceived()`方法根据`RemotingCommand`对象类型调用处理方法：
```java
// 处理request，供子类使用，子类负责根据netty接收到的数据构建RemotingCommand对象
public void processMessageReceived(ChannelHandlerContext ctx, RemotingCommand msg) throws Exception {
    final RemotingCommand cmd = msg;
    if (cmd != null) {
        // 根据RemotingCommand类型处理RemotingCommand对象，RemotingCommand包含了完整的请求或响应数据和信息
        switch (cmd.getType()) {
            case REQUEST_COMMAND:
                processRequestCommand(ctx, cmd);
                break;
            case RESPONSE_COMMAND:
                processResponseCommand(ctx, cmd);
                break;
            default:
                break;
        }
    }
}
```
子类只需要构建`RemotingCommand`对象并调用`processMessageReceived()`方法处理即可，不需要关心应该调用`processRequestCommand()`方法还是`processResponseCommand()`方法。当处理请求时，由`processRequestCommand()`方法负责。该方法的参数`RemotingCommand`对象就是收到的请求对象，其`code`属性表示请求的类型，如11表示`PULL_MESSAGE`，请求的所有类型对应的数字都定义在了`RequestCode`类中，`RequestCode.PULL_MESSAGE`的值就是11。`processRequestCommand()`方法的逻辑是，根据请求的`code`属性从`processorTable`属性中获取其对应的`NettyRequestProcessor`对象，如果不存在则使用`defaultRequestProcessor`。获取到`NettyRequestProcessor`对象后，创建一个执行`NettyRequestProcessor`对象的`processRequest()`方法的`Runnable`对象并将`Runnable`对象封装为`RequestTask`对象，再交由线程池执行，`RequestTask`对象的作用可以看源码中的注释。处理请求后如何返回响应完全由`NettyRequestProcessor`对象决定，所以到此`processRequestCommand()`方法就结束了，下面是该方法的源码：

```java
// 处理收到的请求
public void processRequestCommand(final ChannelHandlerContext ctx, final RemotingCommand cmd) {
    // processorTable以code为key，Pair<NettyRequestProcessor, ExecutorService>为value保存能够处理RemotingCommand的
    // 处理器，即NettyRequestProcessor，NettyRequestProcessor用于处理收到的请求，ExecutorService是执行处理过程的线程池。
    // processorTable由子类负责添加数据，这里根据RemotingCommand的code获取对应的NettyRequestProcessor
    final Pair<NettyRequestProcessor, ExecutorService> matched = this.processorTable.get(cmd.getCode());
    // 如果未找到NettyRequestProcessor则使用默认的NettyRequestProcessor
    final Pair<NettyRequestProcessor, ExecutorService> pair = null == matched ? this.defaultRequestProcessor : matched;
    // opaque为请求的id，每个请求的opaque都不同，响应的opaque等于请求的opaque
    final int opaque = cmd.getOpaque();

    if (pair != null) {
        // 请求的处理是在线程池中做的，这里创建请求处理的线程任务
        Runnable run = new Runnable() {
            @Override
            public void run() {
                try {
                    // 处理请求前调用RPCHook的doBeforeRequest方法
                    doBeforeRpcHooks(RemotingHelper.parseChannelRemoteAddr(ctx.channel()), cmd);
                    // object1就是NettyRequestProcessor
                    final RemotingCommand response = pair.getObject1().processRequest(ctx, cmd);
                    // 处理请求后调用RPCHook的doAfterResponse方法
                    doAfterRpcHooks(RemotingHelper.parseChannelRemoteAddr(ctx.channel()), cmd, response);

                    // oneway的调用方法对response不关心，所以这里只对非oneway的请求做response处理
                    if (!cmd.isOnewayRPC()) {
                        if (response != null) {
                            // 请求和响应的id是一样的
                            response.setOpaque(opaque);
                            // 设置response的flag属性，将其标记为response类型
                            response.markResponseType();
                            try {
                                // 处理请求结束后发送响应
                                ctx.writeAndFlush(response);
                            } catch (Throwable e) {
                                log.error("process request over, but response failed", e);
                                log.error(cmd.toString());
                                log.error(response.toString());
                            }
                        } else {

                        }
                    }
                } catch (Throwable e) {
                    log.error("process request exception", e);
                    log.error(cmd.toString());

                    if (!cmd.isOnewayRPC()) {
                        // 发送异常时返回异常信息
                        final RemotingCommand response = RemotingCommand.createResponseCommand(RemotingSysResponseCode.SYSTEM_ERROR,
                            RemotingHelper.exceptionSimpleDesc(e));
                        response.setOpaque(opaque);
                        ctx.writeAndFlush(response);
                    }
                }
            }
        };

        // 判断是否拒绝请求，可以用于控制请求速率
        if (pair.getObject1().rejectRequest()) {
            // 返回系统繁忙
            final RemotingCommand response = RemotingCommand.createResponseCommand(RemotingSysResponseCode.SYSTEM_BUSY,
                "[REJECTREQUEST]system busy, start flow control for a while");
            response.setOpaque(opaque);
            ctx.writeAndFlush(response);
            return;
        }

        try {
            // RequestTask对象封装了处理请求的线程任务、Channel和RemotingCommand对象。这里用RequestTask对象再封装一次请求处理
            // 的线程任务是因为RequestTask支持stop，RequestTask的run方法在执行真正的线程任务前会判断自己是否被标记为stop了，线程
            // 池的逻辑是在线程数到达其coreSize后将任务放到BlockingQueue中，直到BlockingQueue满了才继续创建线程直到线程数到达
            // maximumPoolSize，所以BlockingQueue中保存了已被提交但是未被运行的任务。这里提交的是RequestTask对象，所以线程池的
            // BlockingQueue中保存的也是RequestTask对象，这样就能够在必要的时候通过BlockingQueue获取等待执行的RequestTask对象
            // 并将其stop
            final RequestTask requestTask = new RequestTask(run, ctx.channel(), cmd);
            pair.getObject2().submit(requestTask);
        } catch (RejectedExecutionException e) {
            // RejectedExecutionException是在线程池队列满了且线程数量到达了maximumPoolSize再提交任务时发生
            if ((System.currentTimeMillis() % 10000) == 0) {
                log.warn(RemotingHelper.parseChannelRemoteAddr(ctx.channel())
                    + ", too many requests and system thread pool busy, RejectedExecutionException "
                    + pair.getObject2().toString()
                    + " request code: " + cmd.getCode());
            }

            // 此时返回系统繁忙
            if (!cmd.isOnewayRPC()) {
                final RemotingCommand response = RemotingCommand.createResponseCommand(RemotingSysResponseCode.SYSTEM_BUSY,
                    "[OVERLOAD]system busy, start flow control for a while");
                response.setOpaque(opaque);
                ctx.writeAndFlush(response);
            }
        }
    } else {
        // 如果没有找到NettyRequestProcessor则返回error
        String error = " request type " + cmd.getCode() + " not supported";
        final RemotingCommand response =
            RemotingCommand.createResponseCommand(RemotingSysResponseCode.REQUEST_CODE_NOT_SUPPORTED, error);
        // 请求和响应的id是一样的
        response.setOpaque(opaque);
        ctx.writeAndFlush(response);
        log.error(RemotingHelper.parseChannelRemoteAddr(ctx.channel()) + error);
    }
}
```

### 发送网络请求处理响应
RocketMQ中一个请求一般都对应着一个响应（除非是oneway类型的请求），所以`NettyRemotingAbstract`中发送请求的实现和处理响应的实现是有联系的，这里就一块分析这两个过程，对于发送请求，有3个方法：
- `invokeSyncImpl` 同步发送请求
- `invokeAsyncImpl` 异步发送请求
- `invokeOnewayImpl` 只发送请求，不关心响应

#### 同步发送请求
对应的方法是`invokeSyncImpl()`，下面是该方法的源码。`invokeSyncImpl()`方法首先获取需要发送的请求的id，之后创建`ResponseFuture`对象，该对象持有请求id和超时时间，作用在后面会说。创建`ResponseFuture`对象后将该对象以请求id为key保存到`responseTable`，之后通过`channel`发送请求对象到服务器，请求对象的编码过程可以看笔记[传输协议](传输协议.md)。`writeAndFlush`方法是个异步方法，所以`invokeSyncImpl`方法添加了一个`ChannelFutureListener`监听操作完成事件，当操作完成时首先判断是否成功，当成功时只需要设置`ResponseFuture`对象的`sendRequestOK`属性为true即可，失败时设置失败原因并调用`putResponse()`方法，监听器这么实现的原因下面会说。异步发送请求之后，调用`responseFuture.waitResponse(timeoutMillis)`方法同步等待，`waitResponse()`方法如下，使用`countDownLatch`最多等待timeout的时间。当等待超时`waitResponse()`方法返回后，其返回值`responseCommand`将为空，此时按照`sendRequestOK`属性的值抛出超时或发送失败的异常即可。：
```java
public RemotingCommand waitResponse(final long timeoutMillis) throws InterruptedException {
    this.countDownLatch.await(timeoutMillis, TimeUnit.MILLISECONDS);
    return this.responseCommand;
}
```

```java
// 同步发送请求
public RemotingCommand invokeSyncImpl(final Channel channel, final RemotingCommand request,
    final long timeoutMillis)
    throws InterruptedException, RemotingSendRequestException, RemotingTimeoutException {
    // 获取请求id
    final int opaque = request.getOpaque();

    try {
        // 同步发送就不需要invokeCallback了
        final ResponseFuture responseFuture = new ResponseFuture(channel, opaque, timeoutMillis, null, null);
        // 发送的请求和请求的响应的opaque也就是请求id是相等的，这里保存请求id和responseFuture的映射关系，待收到响应时能够根据
        // 响应的code也就是opaque获取到这里的responseFuture
        this.responseTable.put(opaque, responseFuture);
        final SocketAddress addr = channel.remoteAddress();
        // writeAndFlush是netty的异步发送数据的方法，这里添加个listener监听操作完成
        channel.writeAndFlush(request).addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(ChannelFuture f) throws Exception {
                if (f.isSuccess()) {
                    // 标记操作成功，这里只是标记成功而没有做其他操作是因为，发送的请求必须对应着收到一个响应才算成功，看下面的
                    // responseFuture.waitResponse(timeoutMillis)可以发现，invokeSyncImpl方法会阻塞直到putResponse
                    // 方法被调用。对于发送成功的请求，只是标记sendRequestOK为true，invokeSyncImpl会阻塞在waitResponse方
                    // 法，直到processResponseCommand方法收到响应后根据响应的code从responseTable中找到responseFuture并
                    // 调用putResponse方法使得waitResponse方法停止阻塞
                    responseFuture.setSendRequestOK(true);
                    return;
                } else {
                    // 标记操作失败
                    responseFuture.setSendRequestOK(false);
                }

                // 操作失败时执行下面的语句
                responseTable.remove(opaque);
                // 如果操作失败则f.cause()就是失败原因
                responseFuture.setCause(f.cause());
                // 发送失败的话是不会有response的，所以这里put一个null，同时putResponse方法会调用ResponseFuture成员变量
                // countDownLatch的countDown方法，使得下面的responseFuture.waitResponse停止等待
                responseFuture.putResponse(null);
                log.warn("send a request command to channel <" + addr + "> failed.");
            }
        });

        // 调用ResponseFuture成员变量countDownLatch的await方法，等待指定的超时时间，或者等到putResponse方法被调用
        RemotingCommand responseCommand = responseFuture.waitResponse(timeoutMillis);
        if (null == responseCommand) {
            // 发送请求成功的条件除了sendRequestOK为true，还要responseCommand不为空，当sendRequestOK为true而
            // responseCommand为空说明请求发送出去了但是没有收到响应，此时上面的waitResponse方法因为putResponse
            // 没有被调用而等待了timeoutMillis的时间，所以此时直接返回timeout
            if (responseFuture.isSendRequestOK()) {
                throw new RemotingTimeoutException(RemotingHelper.parseSocketAddressAddr(addr), timeoutMillis,
                    responseFuture.getCause());
            } else {
                // sendRequestOK为false表示请求发送失败了
                throw new RemotingSendRequestException(RemotingHelper.parseSocketAddressAddr(addr), responseFuture.getCause());
            }
        }

        return responseCommand;
    } finally {
        this.responseTable.remove(opaque);
    }
}
```

`invokeSyncImpl()`方法的分析到此结束，再分析处理响应的过程。假设`invokeSyncImpl()`方法通过`channel`发送请求成功，`ResponseFuture`对象的`sendRequestOK`属性为true，则在超时之前，`invokeSyncImpl()`方法会阻塞在`responseFuture.waitResponse(timeoutMillis)`，现在看处理响应的`processResponseCommand()`方法，下面是该方法的源码。处理响应的过程是，根据响应的`opaque`属性从`responseTable`获取其对应的请求的`ResponseFuture`对象，之后判断`ResponseFuture`对象的`getInvokeCallback()`方法是否为空再进行不同的处理。`getInvokeCallback()`方法的返回值是异步发送请求时的回调对象，所以`invokeSyncImpl()`方法创建`ResponseFuture`对象时传入的`InvokeCallback`属性为空，那么对于同步发送的请求，`processResponseCommand()`方法获取到的`ResponseFuture`对象的`getInvokeCallback()`方法返回的就是空，所以对于同步发送的请求对应的响应，处理过程是：
```java
// 否则是同步调用，这里调用putResponse使得invokeSyncImpl方法停止阻塞
responseFuture.putResponse(cmd);
// 释放当前请求占用的信号量
responseFuture.release();
```
对于同步发送的请求，没有申请许可的过程，所以`release()`方法是否调用无所谓，关于申请许可，可以看后面的异步发送请求和发送oneway请求的过程。关键在于`ResponseFuture`对象的`putResponse()`方法，代码：
```java
public void putResponse(final RemotingCommand responseCommand) {
    this.responseCommand = responseCommand;
    this.countDownLatch.countDown();
}
```
该方法和`ResponseFuture`对象的`waitResponse()`方法对应，当`putResponse()`方法被调用后，`invokeSyncImpl()`方法就从`waitResponse()`方法的阻塞中苏醒，从而能够返回结果。所以`invokeSyncImpl()`方法的同步实际上是使用`ResponseFuture`对象的`countDownLatch`实现的，其同步等待发送的请求对应的响应，并在收到后返回结果。

```java
// 处理收到的响应
public void processResponseCommand(ChannelHandlerContext ctx, RemotingCommand cmd) {
    // 获取响应对应的请求的id
    final int opaque = cmd.getOpaque();
    // processResponseCommand方法的逻辑结合invokeSyncImpl方法的实现就能够理解，可以先看invokeSyncImpl方法的实现再回到这里。
    // 这里根据发送出去的请求的id获取对应的responseFuture
    final ResponseFuture responseFuture = responseTable.get(opaque);
    if (responseFuture != null) {
        // cmd就是响应的内容和相关信息
        responseFuture.setResponseCommand(cmd);

        responseTable.remove(opaque);

        // 如果存在invokeCallback则为异步调用，这里调用executeInvokeCallback方法通知异步调用方操作执行完成，对于异步调用，
        // 不需要执行else语句中的方法。
        if (responseFuture.getInvokeCallback() != null) {
            executeInvokeCallback(responseFuture);
        } else {
            // 否则是同步调用，这里调用putResponse使得invokeSyncImpl方法停止阻塞
            responseFuture.putResponse(cmd);
            // 释放当前请求占用的信号量
            responseFuture.release();
        }
    } else {
        // oneway类型的请求在responseTable中不存在记录
        log.warn("receive response, but not matched any request, " + RemotingHelper.parseChannelRemoteAddr(ctx.channel()));
        log.warn(cmd.toString());
    }
}
```

#### 异步发送请求
对应的方法是`invokeAsyncImpl()`，下面是该方法的源码。异步发送请求前先从`semaphoreAsync`中获取许可，这样能够防止无限制的异步发送请求导致接收请求的服务器压力过大。获取许可最大等待时间为`timeoutMillis`，获取失败时抛出超时异常即可。当获取成功时，`invokeAsyncImpl()`方法创建`SemaphoreReleaseOnlyOnce`对象，该对象能够保证传入的`Semaphore`对象的`release()`方法在其内部最多只会被调用一次，主要实现如下：
```java
public void release() {
    if (this.semaphore != null) {
        if (this.released.compareAndSet(false, true)) {
            this.semaphore.release();
        }
    }
}
```
通过这种方式，能够保证`SemaphoreReleaseOnlyOnce`对象的`release()`方法调用超过1次和只调用1次的结果是相等的，这样就能够安全的多次调用`SemaphoreReleaseOnlyOnce`对象的`release()`方法，方便使用。在创建`SemaphoreReleaseOnlyOnce`对象后，`invokeAsyncImpl()`方法和`invokeSyncImpl()`方法一样创建了`ResponseFuture`对象，只不过在构造函数中传入了`InvokeCallback`对象和`SemaphoreReleaseOnlyOnce`对象。再之后的处理过程和`invokeSyncImpl()`方法类似（在发送失败的情况下由`requestFail()`方法处理，该方法的作用是通过`invokeCallback`通知调用方操作结束），这里不再赘述。对于异步发送的请求，其响应也由`processResponseCommand()`方法处理，和同步请求不同的是，异步请求对应的`ResponseFuture`对象其`getInvokeCallback()`方法返回值不为空，所以异步请求的响应会执行下面的语句：
```java
executeInvokeCallback(responseFuture);
```
`executeInvokeCallback()`方法的代码很简单：
```java
private void executeInvokeCallback(final ResponseFuture responseFuture) {
    boolean runInThisThread = false;
    // 由子类实现，返回专门用于执行executeInvokeCallback的线程池
    ExecutorService executor = this.getCallbackExecutor();
    if (executor != null) {
        try {
            executor.submit(new Runnable() {
                @Override
                public void run() {
                    try {
                        responseFuture.executeInvokeCallback();
                    } catch (Throwable e) {
                        log.warn("execute callback in executor exception, and callback throw", e);
                    } finally {
                        responseFuture.release();
                    }
                }
            });
        } catch (Exception e) {
            runInThisThread = true;
            log.warn("execute callback in executor exception, maybe executor busy", e);
        }
    } else {
        runInThisThread = true;
    }

    // 如果不存在线程池或线程池满了则用当前线程执行executeInvokeCallback
    if (runInThisThread) {
        try {
            responseFuture.executeInvokeCallback();
        } catch (Throwable e) {
            log.warn("executeInvokeCallback Exception", e);
        } finally {
            responseFuture.release();
        }
    }
}
```
`executeInvokeCallback()`方法获取线程池并由线程池调用`responseFuture`对象的`executeInvokeCallback()`方法，而对于`responseFuture`对象的`executeInvokeCallback()`方法，实现也很简单：
```java
public void executeInvokeCallback() {
    if (invokeCallback != null) {
        if (this.executeCallbackOnlyOnce.compareAndSet(false, true)) {
            invokeCallback.operationComplete(this);
        }
    }
}
```
这就实现了在收到异步请求的响应时通知调用方操作结束。

另外一个需要注意的场景是，当请求发送出去了，但是响应没有返回或返回超时，这种情况下`processResponseCommand()`方法不会被调用或在超时时间之后才会被调用，此时应该如何通知调用方？这一过程的实现是在`scanResponseTable()`方法，该方法源码如下：
```java
public void scanResponseTable() {
    final List<ResponseFuture> rfList = new LinkedList<ResponseFuture>();
    // responseTable保存了请求对应的ResponseFuture对象（除了oneway类型的请求），这里检查这些ResponseFuture是否超时（在timeout
    // 基础上再加上1秒作为超时时间）。所有超时的ResponseFuture都保存到rfList在最后分别调用executeInvokeCallback方法，该方法会执
    // 行ResponseFuture的invokeCallback方法通知调用方操作完成了。ResponseFuture的实现保证了对于一个ResponseFuture多次调用
    // executeInvokeCallback是幂等的。针对scanResponseTable的这种超时机制，ResponseFuture的invokeCallback的实现，也就是调用
    // 方传入的invokeCallback中需要判断异步操作完成时是否超时
    Iterator<Entry<Integer, ResponseFuture>> it = this.responseTable.entrySet().iterator();
    while (it.hasNext()) {
        Entry<Integer, ResponseFuture> next = it.next();
        ResponseFuture rep = next.getValue();

        // beginTimestamp默认等于ResponseFuture对象的创建时间。当前遍历的Iterator中即包含同步请求的ResponseFuture对象，也包含
        // 异步请求的ResponseFuture对象。但是同步请求理论上是不会满足下面的条件的，因为同步请求通过ResponseFuture对象的
        // waitResponse方法，最多等待timeoutMillis的时间就返回了，这里在timeout的基础上加了1秒，所以通过请求肯定已经收到响应或
        // 超时了。
        /// todo: 上面是一种这里需要加1秒的解释，还有没有其他原因？如果只是上面的原因，1秒也太久了吧。
        if ((rep.getBeginTimestamp() + rep.getTimeoutMillis() + 1000) <= System.currentTimeMillis()) {
            // 每个ResponseFuture都有一个只能调用一次release方法的Semaphore，该Semaphore在创建ResponseFuture时传入，可以用于
            // 控制ResponseFuture的数量。这里将超时的ResponseFuture释放以许可一个新的ResponseFuture
            rep.release();
            it.remove();
            rfList.add(rep);
            log.warn("remove timeout request, " + rep);
        }
    }

    // 依次调用超时的ResponseFuture的executeInvokeCallback方法通知调用方异步操作完成
    for (ResponseFuture rf : rfList) {
        try {
            executeInvokeCallback(rf);
        } catch (Throwable e) {
            log.warn("scanResponseTable, operationComplete Exception", e);
        }
    }
}
```
`scanResponseTable()`方法扫描所有`responseTable`中的`ResponseFuture`对象，在找到超时的`ResponseFuture`对象后，逐个调用`executeInvokeCallback()`方法。当调用方收到通知后，通过判断`ResponseFuture`对象的`isTimeout()`方法即可直到是不是超时了。`scanResponseTable()`方法`NettyRemotingAbstract`类不负责调用，由子类通过定时任务定时调用。

```java
// 异步发送请求
public void invokeAsyncImpl(final Channel channel, final RemotingCommand request, final long timeoutMillis,
    final InvokeCallback invokeCallback)
    throws InterruptedException, RemotingTooMuchRequestException, RemotingTimeoutException, RemotingSendRequestException {
    // 记录开始时间
    long beginStartTime = System.currentTimeMillis();
    // 记录请求id
    final int opaque = request.getOpaque();
    // 获取许可证
    boolean acquired = this.semaphoreAsync.tryAcquire(timeoutMillis, TimeUnit.MILLISECONDS);
    if (acquired) {
        // once确保传入的semaphoreAsync的release只会被调用一次
        final SemaphoreReleaseOnlyOnce once = new SemaphoreReleaseOnlyOnce(this.semaphoreAsync);
        // 计算获取许可花费的时间
        long costTime = System.currentTimeMillis() - beginStartTime;
        if (timeoutMillis < costTime) {
            // 超时则释放资源并抛出timeout
            once.release();
            throw new RemotingTimeoutException("invokeAsyncImpl call timeout");
        }

        // timeout需要减去获取许可花费的时间，完成请求发送并收到响应或等待响应超时时会通过invokeCallback通知调用方
        // 可以发现异步发送请求成功后等待响应的超时通知调用方依赖scanResponseTable方法，所以异步发送请求时设置的超
        // 时时间不一定准确，依赖scanResponseTable方法的调用频率
        /// todo: 这样感觉timeout可能会和实际timeout的时间有很大出路，可能是我疏忽了啥关键点，看源码实现我是认为会有很大出路的。
        final ResponseFuture responseFuture = new ResponseFuture(channel, opaque, timeoutMillis - costTime, invokeCallback, once);
        this.responseTable.put(opaque, responseFuture);
        try {
            // 发送请求并添加listener
            channel.writeAndFlush(request).addListener(new ChannelFutureListener() {
                @Override
                public void operationComplete(ChannelFuture f) throws Exception {
                    if (f.isSuccess()) {
                        // 发送成功只需要设置sendRequestOK为true，在获取到响应后由processResponseCommand方法通知调用方操作
                        // 结束
                        responseFuture.setSendRequestOK(true);
                        return;
                    }
                    // 发送失败则设置sendRequestOK为false并调用invokeCallback通知调用方操作结束
                    requestFail(opaque);
                    log.warn("send a request command to channel <{}> failed.", RemotingHelper.parseChannelRemoteAddr(channel));
                }
            });
        } catch (Exception e) {
            // 释放信号量
            responseFuture.release();
            log.warn("send a request command to channel <" + RemotingHelper.parseChannelRemoteAddr(channel) + "> Exception", e);
            throw new RemotingSendRequestException(RemotingHelper.parseChannelRemoteAddr(channel), e);
        }
    } else {
        // 调用invokeAsyncImpl方法的地方对timeoutMillis都做了验证，不确定什么情况下会导致timeoutMillis <= 0
        if (timeoutMillis <= 0) {
            throw new RemotingTooMuchRequestException("invokeAsyncImpl invoke too fast");
        } else {
            // 通过异常告知调用方许可证数量不足
            String info =
                String.format("invokeAsyncImpl tryAcquire semaphore timeout, %dms, waiting thread nums: %d semaphoreAsyncValue: %d",
                    timeoutMillis,
                    this.semaphoreAsync.getQueueLength(),
                    this.semaphoreAsync.availablePermits()
                );
            log.warn(info);
            throw new RemotingTimeoutException(info);
        }
    }
}
```

#### 只发送请求，不关心响应
对应的方法是`invokeOnewayImpl()`，下面是该方法的源码。oneway类型的请求不关心是否发送成功，也不关心是否收到响应，只想要异步发送出去。`invokeOnewayImpl()`方法和`invokeAsyncImpl()`方法的实现类型，不同的地方在于，`invokeOnewayImpl()`方法调用`RemotingCommand`对象的`markOnewayRPC()`方法将其标记为oneway类型的，这样当服务器的`processRequestCommand()`方法收到oneway类型的请求时只需要处理而不需要管处理的结果。另外由于oneway类型的请求不关心是否发送成功，不需要操作结束的通知，同时也不关心是否收到响应，服务器收到oneway类型的请求也不会返回响应，所以就不需要创建`ResponseFuture`对象，此时oneway类型的请求的许可释放就在`invokeOnewayImpl()`方法中处理了，在发生异常或收到netty的操作完成时间是释放即可。
```java
// 只发送请求，不关心响应，所以没有同步等待，也没有invokeCallback，看下面的处理过程可以发现，调用方使用invokeOnewayImpl
// 方法是不能确定请求是否发送成功或是否收到响应的
public void invokeOnewayImpl(final Channel channel, final RemotingCommand request, final long s)
    throws InterruptedException, RemotingTooMuchRequestException, RemotingTimeoutException, RemotingSendRequestException {
    // 设置request的flag属性标记该请求是onewayRPC
    request.markOnewayRPC();
    // 获取许可
    boolean acquired = this.semaphoreOneway.tryAcquire(timeoutMillis, TimeUnit.MILLISECONDS);
    if (acquired) {
        final SemaphoreReleaseOnlyOnce once = new SemaphoreReleaseOnlyOnce(this.semaphoreOneway);
        try {
            channel.writeAndFlush(request).addListener(new ChannelFutureListener() {
                @Override
                public void operationComplete(ChannelFuture f) throws Exception {
                    // 对于oneway，没有创建request对应的ResponseFuture对象，所以processResponseCommand方法不会处理
                    // oneway请求对应的响应，所以需要在这里释放信号量
                    once.release();
                    if (!f.isSuccess()) {
                        // 由于不关心响应，所以发送失败只需要记录日志，所以调用方使用invokeOnewayImpl方法是不能确定请求是否发
                        // 送成功或是否收到响应
                        log.warn("send a request command to channel <" + channel.remoteAddress() + "> failed.");
                    }
                }
            });
        } catch (Exception e) {
            once.release();
            log.warn("write send a request command to channel <" + channel.remoteAddress() + "> failed.");
            throw new RemotingSendRequestException(RemotingHelper.parseChannelRemoteAddr(channel), e);
        }
    } else {
        // 和invokeAsyncImpl的处理过程一样
        if (timeoutMillis <= 0) {
            throw new RemotingTooMuchRequestException("invokeOnewayImpl invoke too fast");
        } else {
            String info = String.format(
                "invokeOnewayImpl tryAcquire semaphore timeout, %dms, waiting thread nums: %d semaphoreAsyncValue: %d",
                timeoutMillis,
                this.semaphoreOneway.getQueueLength(),
                this.semaphoreOneway.availablePermits()
            );
            log.warn(info);
            throw new RemotingTimeoutException(info);
        }
    }
}
```

### 通知`NettyEvent`事件
`NettyEvent`类定义如下：
```java
public class NettyEvent {
    private final NettyEventType type;
    private final String remoteAddr;
    private final Channel channel;

    public NettyEvent(NettyEventType type, String remoteAddr, Channel channel) {
        this.type = type;
        this.remoteAddr = remoteAddr;
        this.channel = channel;
    }

    public NettyEventType getType() {
        return type;
    }

    public String getRemoteAddr() {
        return remoteAddr;
    }

    public Channel getChannel() {
        return channel;
    }

    @Override
    public String toString() {
        return "NettyEvent [type=" + type + ", remoteAddr=" + remoteAddr + ", channel=" + channel + "]";
    }
}

public enum NettyEventType {
    CONNECT,
    CLOSE,
    IDLE,
    EXCEPTION
}
```
可以看到`NettyEvent`事件实际上就是连接相关的事件。`NettyRemotingAbstract`类没有提供创建`NettyEvent`事件的相关方法，因为`NettyRemotingAbstract`类没有底层连接相关的逻辑，`NettyRemotingAbstract`类只提供了一个发送`NettyEvent`事件的逻辑，在其`putNettyEvent()`方法：
```java
public void putNettyEvent(final NettyEvent event) {
    // nettyEventExecutor负责将NettyEvent发送给ChannelEventListener
    this.nettyEventExecutor.putNettyEvent(event);
}
```
`nettyEventExecutor`对象是`NettyRemotingAbstract`类的内部类，定义如下：
```java
// 除非被stop，否则不断接收putNettyEvent方法传入的NettyEvent并通知ChannelEventListener对应的事件
class NettyEventExecutor extends ServiceThread {
    private final LinkedBlockingQueue<NettyEvent> eventQueue = new LinkedBlockingQueue<NettyEvent>();
    private final int maxSize = 10000;

    public void putNettyEvent(final NettyEvent event) {
        if (this.eventQueue.size() <= maxSize) {
            this.eventQueue.add(event);
        } else {
            log.warn("event queue size[{}] enough, so drop this event {}", this.eventQueue.size(), event.toString());
        }
    }

    @Override
    public void run() {
        log.info(this.getServiceName() + " service started");

        final ChannelEventListener listener = NettyRemotingAbstract.this.getChannelEventListener();

        while (!this.isStopped()) {
            try {
                NettyEvent event = this.eventQueue.poll(3000, TimeUnit.MILLISECONDS);
                if (event != null && listener != null) {
                    switch (event.getType()) {
                        case IDLE:
                            listener.onChannelIdle(event.getRemoteAddr(), event.getChannel());
                            break;
                        case CLOSE:
                            listener.onChannelClose(event.getRemoteAddr(), event.getChannel());
                            break;
                        case CONNECT:
                            listener.onChannelConnect(event.getRemoteAddr(), event.getChannel());
                            break;
                        case EXCEPTION:
                            listener.onChannelException(event.getRemoteAddr(), event.getChannel());
                            break;
                        default:
                            break;

                    }
                }
            } catch (Exception e) {
                log.warn(this.getServiceName() + " service has exception. ", e);
            }
        }

        log.info(this.getServiceName() + " service end");
    }

    @Override
    public String getServiceName() {
        return NettyEventExecutor.class.getSimpleName();
    }
}
```
`NettyEventExecutor`类的实现很简单，这里不再赘述。重点在于其父类`ServiceThread`，代码：
```java
public abstract class ServiceThread implements Runnable {
    private static final InternalLogger log = InternalLoggerFactory.getLogger(RemotingHelper.ROCKETMQ_REMOTING);

    private static final long JOIN_TIME = 90 * 1000;
    protected final Thread thread;
    protected volatile boolean hasNotified = false;
    protected volatile boolean stopped = false;

    public ServiceThread() {
        this.thread = new Thread(this, this.getServiceName());
    }

    public abstract String getServiceName();

    public void start() {
        this.thread.start();
    }

    public void shutdown() {
        this.shutdown(false);
    }

    public void shutdown(final boolean interrupt) {
        this.stopped = true;
        log.info("shutdown thread " + this.getServiceName() + " interrupt " + interrupt);
        synchronized (this) {
            // 可能有其他线程会wait在ServiceThread对象，所以当shutdown时，这里唤醒等待的线程
            if (!this.hasNotified) {
                this.hasNotified = true;
                this.notify();
            }
        }

        try {
            if (interrupt) {
                this.thread.interrupt();
            }

            long beginTime = System.currentTimeMillis();
            // 等待线程结束，最多等待jointime的时间
            this.thread.join(this.getJointime());
            long elapsedTime = System.currentTimeMillis() - beginTime;
            log.info("join thread " + this.getServiceName() + " elapsed time(ms) " + elapsedTime + " "
                + this.getJointime());
        } catch (InterruptedException e) {
            log.error("Interrupted", e);
        }
    }

    public long getJointime() {
        return JOIN_TIME;
    }

    public boolean isStopped() {
        return stopped;
    }
}
```
`ServiceThread`类提供了一个`shutdown`方法优雅的停止线程，子类只要能够响应中断，并且尊重`stopped`属性的值，就能停止运行，从`NettyEventExecutor`类的`run()`方法的实现就能理解如果停止运行。