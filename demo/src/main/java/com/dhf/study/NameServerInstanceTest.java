package com.dhf.study;

import org.apache.rocketmq.common.namesrv.NamesrvConfig;
import org.apache.rocketmq.namesrv.NamesrvController;
import org.apache.rocketmq.remoting.netty.NettyServerConfig;

public class NameServerInstanceTest {
    public static void main(String[] args) throws Exception {
        // NamesrvConfig配置
        final NamesrvConfig namesrvConfig = new NamesrvConfig();
        // NettyServerConfig配置
        final NettyServerConfig nettyServerConfig = new NettyServerConfig();
        nettyServerConfig.setListenPort(9876); // 设置端口
        // 创建NamesrvController对象，并启动
        NamesrvController namesrvController = new NamesrvController(namesrvConfig, nettyServerConfig);
        namesrvController.initialize();
        namesrvController.start();
    }
}
