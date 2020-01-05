# remoting模块的作用和实现

# 作用
RocketMQ节点间的通信都靠remoting模块，所以RocketMQ的client（包括consumer和producer）、broker和namesrv都依赖该模块。

remoting模块提供了一个抽象类`NettyRemotingAbstract`，该类实现了发送请求、接收请求和响应的基本逻辑，无论作为客户端向服务器发送请求，还是作为服务器接收请求，都需要用到该类。该类的实现类只需要实现请求和响应对应的底层网络数据的发送、接收、编码和解码过程。remoting模块基于netty，提供了两个`NettyRemotingAbstract`的实现类：`NettyRemotingClient`和`NettyRemotingServer`，remoting模块的逻辑都集中在这3个类中，下面分别分析这3个类及其他功能点的实现：
- [数据传输协议](数据传输协议.md)
- [`NettyRemotingAbstract`类的实现](NettyRemotingAbstract类的实现.md)