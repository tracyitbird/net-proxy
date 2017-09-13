package com.villcore.net.proxy.v2.server;

import com.villcore.net.proxy.v2.Html404;
import com.villcore.net.proxy.v2.client.ChildHandlerInitlizer;
import com.villcore.net.proxy.v2.client.ConnectionManager;
import com.villcore.net.proxy.v2.client.PackageQeueu;
import com.villcore.net.proxy.v2.pkg.PackageUtils;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 服务端 ChildChannel Handler 初始化
 */
public class ServerChildHandlerInitlizer extends ChannelInitializer<SocketChannel> {
    private static final Logger LOG = LoggerFactory.getLogger(ServerChildHandlerInitlizer.class);

    private ChannelInboundHandlerAdapter packageGather;
    private PackageQeueu packageQeueu;
    private ConnectionManager connectionManager;
    private ServerChannelSendService serverChannelSendService;

    public ServerChildHandlerInitlizer(PackageQeueu packageQeueu, ConnectionManager connectionManager, ServerChannelSendService serverChannelSendService) {
        this.packageQeueu = packageQeueu;
        this.connectionManager = connectionManager;
        packageGather = new PackageGatherHandler(packageQeueu);
        this.serverChannelSendService = serverChannelSendService;
    }

    @Override
    protected void initChannel(SocketChannel ch) throws Exception {
        serverChannelSendService.setClientChannel((NioSocketChannel) ch);
        serverChannelSendService.start();
        new Thread(serverChannelSendService, "server-send-service").start();

        LOG.debug("server get client channel [{}]...", ch.remoteAddress());
        ch.pipeline().addLast(new PackageDecoder());
        ch.pipeline().addLast(packageGather);
    }
}