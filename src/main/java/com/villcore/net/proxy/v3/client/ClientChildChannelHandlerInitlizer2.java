package com.villcore.net.proxy.v3.client;

import com.villcore.net.proxy.v3.common.Connection;
import com.villcore.net.proxy.v3.common.ConnectionManager;
import com.villcore.net.proxy.v3.common.Tunnel;
import com.villcore.net.proxy.v3.common.TunnelManager;
import com.villcore.net.proxy.v3.pkg.ChannelClosePackage;
import com.villcore.net.proxy.v3.pkg.PackageUtils;
import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;

/**
 * 客户端ChaildChannelHandlerInitlizer
 *
 * 该类主要在建立客户端代理channel后，将channel保存为对应Tunnel对象，加入管理
 */
public class ClientChildChannelHandlerInitlizer2 extends ChannelInitializer<Channel> {
    private static final Logger LOG = LoggerFactory.getLogger(ClientChildChannelHandlerInitlizer2.class);

    private TunnelManager tunnelManager;
    private ConnectionManager connectionManager;
    private String remoteAddr;
    private int remotePort;

    public ClientChildChannelHandlerInitlizer2(TunnelManager tunnelManager, ConnectionManager connectionManager, String remoteAddr, int remotePort) {
        this.tunnelManager = tunnelManager;
        this.connectionManager = connectionManager;
        this.remoteAddr = remoteAddr;
        this.remotePort = remotePort;
    }

    @Override
    protected void initChannel(Channel channel) throws Exception {
//        channel.config().setAllocator(UnpooledByteBufAllocator.DEFAULT);

        Connection connection = connectionManager.getConnection(remoteAddr, remotePort);
        Tunnel tunnel = tunnelManager.newTunnel(channel);
        LOG.debug("init tunnel [{}] for channel [{}]...", tunnel.getConnId(), channel.remoteAddress().toString());

        channel.closeFuture().addListener(new GenericFutureListener<Future<? super Void>>() {
            @Override
            public void operationComplete(Future<? super Void> future) throws Exception {
                if(future.isSuccess()) {
                    tunnel.needClose();
                    ChannelClosePackage channelClosePackage = PackageUtils
                            .buildChannelClosePackage(tunnel.getConnId(), tunnel.getCorrespondConnId(), 1L);
                    connection.addSendPackages(tunnel.drainSendPackages());
                    connection.addSendPackages(Collections.singletonList(channelClosePackage));
                    tunnel.close();
                }
            }
        });

        tunnel.setBindConnection(connection);
        tunnelManager.bindConnection(connection, tunnel);
        channel.pipeline().addLast(new ClientTunnelChannelReadHandler(tunnelManager, connection));
        //channel.pipeline().addLast(new PackageToByteBufOutHandler());
    }
}