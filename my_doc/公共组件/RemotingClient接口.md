`RemotingClient`接口定义了作为一个客户端时需要实现的方法，其继承自`RemotingService`接口，`RemotingService`接口定义了作为一个远程服务需要实现的方法：
```java
public interface RemotingService {
    // 开启服务器
    void start();

    // 关闭服务器
    void shutdown();

    // RPCHook用于发送请求前后和处理请求前后执行回调
    void registerRPCHook(RPCHook rpcHook);
}
```

`RemotingClient`接口在`RemotingService`接口基础上，添加了作为服务器需要实现的方法：
```java
public interface RemotingClient extends RemotingService {
    // 更新可用的namesrv地址
    void updateNameServerAddressList(final List<String> addrs);

    // 获取可用的namesrv地址
    List<String> getNameServerAddressList();

    // 同步发送请求
    RemotingCommand invokeSync(final String addr, final RemotingCommand request,
        final long timeoutMillis) throws InterruptedException, RemotingConnectException,
        RemotingSendRequestException, RemotingTimeoutException;

    // 异步发送请求
    void invokeAsync(final String addr, final RemotingCommand request, final long timeoutMillis,
        final InvokeCallback invokeCallback) throws InterruptedException, RemotingConnectException,
        RemotingTooMuchRequestException, RemotingTimeoutException, RemotingSendRequestException;

    // 以oneway的方式发送请求
    void invokeOneway(final String addr, final RemotingCommand request, final long timeoutMillis)
        throws InterruptedException, RemotingConnectException, RemotingTooMuchRequestException,
        RemotingTimeoutException, RemotingSendRequestException;

    // 注册请求处理器
    void registerProcessor(final int requestCode, final NettyRequestProcessor processor,
        final ExecutorService executor);

    // 执行responseFuture对象的executeInvokeCallback方法的线程池，和RemotingServer不同，RemotingClient多了这个方法使得线程池可
    // 变
    void setCallbackExecutor(final ExecutorService callbackExecutor);

    // 返回上面的线程池
    ExecutorService getCallbackExecutor();

    boolean isChannelWritable(final String addr);
}
```