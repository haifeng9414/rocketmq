`RemotingServer`接口定义了作为一个服务器时需要实现的方法，其继承自`RemotingService`接口，`RemotingService`接口定义了作为一个远程服务需要实现的方法：
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

`RemotingServer`接口在`RemotingService`接口基础上，添加了作为服务器需要实现的方法：
```java
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
```

上面`getProcessorPair()`方法的返回值`Pair`类的定义很简单，表示一对对象：
```java
public class Pair<T1, T2> {
    private T1 object1;
    private T2 object2;

    public Pair(T1 object1, T2 object2) {
        this.object1 = object1;
        this.object2 = object2;
    }

    public T1 getObject1() {
        return object1;
    }

    public void setObject1(T1 object1) {
        this.object1 = object1;
    }

    public T2 getObject2() {
        return object2;
    }

    public void setObject2(T2 object2) {
        this.object2 = object2;
    }
}
```