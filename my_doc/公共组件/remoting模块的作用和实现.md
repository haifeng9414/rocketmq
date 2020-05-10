# remoting模块的作用和实现

# 作用
RocketMQ节点间的通信都靠remoting模块，所以RocketMQ的client（包括consumer和producer）、broker和namesrv都依赖该模块。

remoting模块提供了一个抽象类`NettyRemotingAbstract`，该类实现了发送请求、接收请求和响应的基本逻辑，无论作为客户端向服务器发送请求，还是作为服务器接收请求，都需要用到该类。该类的实现类只需要实现请求和响应对应的底层网络数据的发送、接收、编码和解码过程。remoting模块基于netty，提供了两个`NettyRemotingAbstract`的实现类：`NettyRemotingClient`和`NettyRemotingServer`，remoting模块的逻辑都集中在这3个类中，下面分别分析这3个类及相关功能点的实现：
- 传输协议的实现：涉及到RocketMQ中所有组件RPC时的数据传输格式、编码和解码，分析在[传输协议的实现](传输协议.md)
- `RemotingServer`接口：`RemotingServer`接口定义了作为服务器需要实现的方法，包括启停、注册请求的处理器等方法，`RemotingServer`接口的定义在[RemotingServer接口](RemotingServer接口.md)
- `RemotingClient`接口：`RemotingClient`接口定义了作为客户端需要实现的方法，包括更新或获取namesrv地址、以不同的方式发送请求等方法，`RemotingClient`接口的定义在[RemotingClient接口](RemotingClient接口.md)
- `NettyRemotingAbstract`类：[传输协议的实现](传输协议.md)封装了网路数据到对象的编码和解码，`NettyRemotingAbstract`类只依赖解码后得到的对象，将RocketMQ中发送请求、接收请求和响应的逻辑抽象了出来。`NettyRemotingAbstract`类不涉及传输层的实现，也就是不关心如何收到网络数据及如何发送网络数据，分析在[NettyRemotingAbstract的实现](NettyRemotingAbstract类的实现.md)
- `NettyRemotingServer`类：`NettyRemotingServer`是`NettyRemotingAbstract`的子类，同时实现了`RemotingServer`接口。`NettyRemotingServer`通过netty实现了网络数据的监听，并实现了作为服务器时的所有功能，RocketMQ中所有能够作为服务器接收请求的组件的服务器实现都是该类，分析在[NettyRemotingServer的实现](NettyRemotingServer类的实现.md)
- `NettyRemotingClient`类：`NettyRemotingClient`也是`NettyRemotingAbstract`的子类，同时实现了`RemotingClient`接口。`NettyRemotingClient`类的作用主要是发送请求给服务器，所有rocketmq集群中所有能够发送请求的组件都初始化了该类的实例，包括broker、consumer、producer，`NettyRemotingClient`类的实现分析在[NettyRemotingClient的实现](NettyRemotingClient类的实现.md)