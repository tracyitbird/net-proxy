package com.villcore.net.proxy.v3.server;

import com.villcore.net.proxy.v3.client.ClientPackageDecoder;
import com.villcore.net.proxy.v3.client.ConnectionRecvPackageGatherHandler;
import com.villcore.net.proxy.v3.client.PackageToByteBufOutHandler;
import com.villcore.net.proxy.v3.common.*;
import com.villcore.net.proxy.v3.pkg.ChannelClosePackage;
import com.villcore.net.proxy.v3.pkg.PackageUtils;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;

/**
 * 服务端 ChildChannel Handler 初始化
 */
public class ServerChildHandlerInitlizer extends ChannelInitializer<SocketChannel> {
    private static final Logger LOG = LoggerFactory.getLogger(ServerChildHandlerInitlizer.class);

    private ConnectionManager connectionManager;
    private TunnelManager tunnelManager;

    public ServerChildHandlerInitlizer(ConnectionManager connectionManager, TunnelManager tunnelManager) {
        this.connectionManager = connectionManager;
        this.tunnelManager = tunnelManager;
    }

    @Override
    protected void initChannel(SocketChannel ch) throws Exception {
        Connection connection = connectionManager.acceptConnectTo(ch);

//        Channel channel = ch;
//        channel.closeFuture().addListener(new GenericFutureListener<Future<? super Void>>() {
//            @Override
//            public void operationComplete(Future<? super Void> future) throws Exception {
//                if(future.isSuccess()) {
//                    Tunnel tunnel = tunnelManager.tunnelFor(channel);
//                    tunnel.shouldClose();
//                    ChannelClosePackage channelClosePackage = PackageUtils
//                            .buildChannelClosePackage(tunnel.getConnId(), tunnel.getCorrespondConnId(), 1L);
//                    connection.addSendPackages(Collections.singletonList(channelClosePackage));
//                }
//            }
//        });

        ch.pipeline().addLast(new ClientPackageDecoder());
        //ch.pipeline().addLast(new ConnectionPackageDecoder());
        ch.pipeline().addLast(new ConnIdConvertChannelHandler2());
        ch.pipeline().addLast(new ConnectionRecvPackageGatherHandler(connectionManager));
        //ch.pipeline().addLast(new ConnIdConvertChannelHandler());
        ch.pipeline().addLast(new PackageToByteBufOutHandler());
        ch.pipeline().writeAndFlush(Unpooled.EMPTY_BUFFER);
    }
}
