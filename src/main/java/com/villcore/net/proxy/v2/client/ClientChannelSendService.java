package com.villcore.net.proxy.v2.client;

import com.sun.xml.internal.bind.v2.model.core.ID;
import com.villcore.net.proxy.v2.Html404;
import com.villcore.net.proxy.v2.pkg.ConnectPackage;
import com.villcore.net.proxy.v2.pkg.DefaultDataPackage;
import com.villcore.net.proxy.v2.pkg.Package;
import com.villcore.net.proxy.v2.pkg.PackageUtils;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.FutureListener;
import io.netty.util.concurrent.GenericFutureListener;
import io.netty.util.concurrent.Promise;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.Charset;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 客户端发送服务
 * <p>
 *
 * 主要执行以下操作
 *
 * 1.对于
 * 2.将聚合的请求package发送到远程服务器通道
 * 3.从远程服务器通道读取相应package，并根据connId分发给本地channnel
 */
public class ClientChannelSendService implements Runnable {
    private static final Logger LOG = LoggerFactory.getLogger(ClientChannelSendService.class);

    private static final long IDLE_SLEEP_MICROSECONDS = 50;
    private volatile boolean running;

    private ConnectionManager connectionManager;

    private PackageQeueu sendPackage;
    private PackageQeueu recvPackage;
    private PackageQeueu failedSendPackage;

    private EventLoopGroup eventExecutors;
    private NioSocketChannel remoteSocketChannel;

    private final Bootstrap bootstrap = new Bootstrap();

    private String remoteAddr;
    private int port;

    RemoteReadHandler remoteReadHandler = new RemoteReadHandler();

    public ClientChannelSendService(ConnectionManager connectionManager, PackageQeueu sendPackage, PackageQeueu recvPackage, PackageQeueu failedSendPackage, EventLoopGroup eventExecutors, String remoteAddr, int port) {
        this.connectionManager = connectionManager;
        this.sendPackage = sendPackage;
        this.recvPackage = recvPackage;
        this.failedSendPackage = failedSendPackage;
        this.eventExecutors = eventExecutors;
        this.remoteSocketChannel = remoteSocketChannel;
        this.remoteAddr = remoteAddr;
        this.port = port;

        bootstrap.group(this.eventExecutors)
                .channel(NioSocketChannel.class)
                .handler(remoteReadHandler);
    }

    //远程读取handler
    @ChannelHandler.Sharable
    private static class RemoteReadHandler extends ChannelInboundHandlerAdapter {
        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            LOG.debug("get remote resp...");
        }
    };


    public void start() {
        running = true;
    }

    public void stop() {
        running = false;
    }

    private void connectServer(String addr, int port) throws InterruptedException {
        LOG.debug("connect to server...");
        try {
            Channel channel = bootstrap.connect(remoteAddr, port).sync().channel();
            channel.closeFuture().addListener(new GenericFutureListener<Future<? super Void>>() {
                @Override
                public void operationComplete(Future<? super Void> future) throws Exception {
                    LOG.debug("remote server channel closed...");
                }
            });
            remoteSocketChannel = (NioSocketChannel) channel;
        } catch (Exception e) {
            LOG.error(e.getMessage(), e);
        }
//        Promise<Channel> promise = this.eventExecutors.next().newPromise();
//        promise.addListener(
//                new FutureListener<Channel>() {
//                    @Override
//                    public void operationComplete(final Future<Channel> future) throws Exception {
//                        final Channel outboundChannel = future.getNow();
//                        if (future.isSuccess()) {
//                            remoteSocketChannel = (NioSocketChannel) outboundChannel;
//                            outboundChannel.pipeline().addLast(remoteReadHandler);
//                            LOG.debug("connected remote server [{}] ...", addr);
//                        } else {
//                            LOG.debug("connect remote server [{}] failed...", port);
//                            remoteSocketChannel = null;
//                        }
//                    }
//                });
//        if(remoteSocketChannel == null || !remoteSocketChannel.isOpen()) {
//            bootstrap.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10000)
//                    .option(ChannelOption.SO_KEEPALIVE, true)
//                    .handler(new DirectClientHandler(promise));
//
//            bootstrap.connect(addr, port).addListener(new ChannelFutureListener() {
//                @Override
//                public void operationComplete(ChannelFuture future) throws Exception {
//                    if (future.isSuccess()) {
//                    } else {
//                        LOG.debug("connect remote server [{}] failed...", addr);
//                        remoteSocketChannel = null;
//                    }
//                }
//            });
    }

    private void checkRemoteServerConnectState() throws InterruptedException {
        //LOG.debug("======================{}, {}", remoteSocketChannel == null, remoteSocketChannel == null ? true : !remoteSocketChannel.isOpen());
        if (remoteSocketChannel == null || !remoteSocketChannel.isOpen()) {
            if(remoteSocketChannel != null) {
                remoteSocketChannel.close().sync();
                remoteSocketChannel = null;
            }
            LOG.debug("remote server is not connected ...");
            connectServer(remoteAddr, port);
        }
    }

    @Override
    public void run() {
        while (!Thread.currentThread().isInterrupted() && running) {
            try {
                checkRemoteServerConnectState();
            } catch (InterruptedException e) {
                LOG.error(e.getMessage(), e);
                Thread.currentThread().interrupt();
            }
            List<Package> sendPackages = sendPackage.drainPackage();
            List<Package> recvPackages = recvPackage.drainPackage();

            if (sendPackages.isEmpty() && recvPackages.isEmpty()) {
                try {
                    TimeUnit.MILLISECONDS.sleep(IDLE_SLEEP_MICROSECONDS);
                    continue;
                } catch (InterruptedException e) {
                    LOG.error(e.getMessage(), e);
                    Thread.currentThread().interrupt();
                }
            }


            //send remote
            sendRemote(sendPackages);
            //send local
            sendLocal(recvPackages);
        }
    }

    public void sendRemote(List<Package> packages) {
        try {
            packages = processSendPackages(packages);
            writeSendPackages(packages);
        } catch (Exception e) {
            LOG.error(e.getMessage(), e);
        }
    }

    public void sendLocal(List<Package> packages) {
        try {
            packages = processRecvPackages(packages);
            writeRecvPackages(packages);
        } catch (Exception e) {
            LOG.error(e.getMessage(), e);
        }
    }


    private void writeSendPackages(List<Package> packages) throws Exception {
        LOG.debug("package size = {}", packages.size());
        int count = 0;
        for (Package pkg : packages) {
            LOG.debug("channel send service write send package ... {}", pkg);
            int localConnId = -1;

            LOG.debug("pkg class = {}, cunt = {}", pkg.getClass(), ++count);
            if (pkg instanceof ConnectPackage) {
                ConnectPackage connectPackage = (ConnectPackage) pkg;
                localConnId = connectPackage.getLocalConnectionId();
                //LOG.debug(">>>connect package...{}", PackageUtils.toString(pkg));
            }

            if (pkg instanceof DefaultDataPackage) {
                DefaultDataPackage defaultDataPackage = (DefaultDataPackage) pkg;
                localConnId = defaultDataPackage.getLocalConnectionId();
                //LOG.debug(">>>data package...{}", PackageUtils.toString(pkg));
            }



//            DefaultDataPackage testPkg = new DefaultDataPackage();
//            ByteBuf header = DefaultDataPackage.newHeader(localConnId, -1, 1L);
//            testPkg.setHeader(header);
//            testPkg.setBody(Unpooled.wrappedBuffer(Html404.RESP.getBytes()));
//
//            recvPackage.putPackage(testPkg);

            if (remoteSocketChannel == null || !remoteSocketChannel.isOpen()) {
                LOG.debug("remote server channel does not connected...");
                failedSendPackage.putPackage(pkg);
                continue;
            }

//            LOG.debug("pkg len = {}", pkg.toByteBuf().readableBytes());
//            String test = "this is a test...";
//            ByteBuf testBuf = Unpooled.wrappedBuffer(Unpooled.buffer().writeInt(test.getBytes().length), Unpooled.wrappedBuffer(test.getBytes()));
//            remoteSocketChannel.writeAndFlush(testBuf);

            //
            remoteSocketChannel.writeAndFlush(pkg.toByteBuf());
            remoteSocketChannel.writeAndFlush(Unpooled.EMPTY_BUFFER);
        }
    }

    private void writeRecvPackages(List<Package> packages) throws Exception {
        for (Package pkg : packages) {
            LOG.debug("channel send service write recv package ... {}", pkg);
            int localConnId = -1;
            if (pkg instanceof ConnectPackage) {
                ConnectPackage connectPackage = (ConnectPackage) pkg;
                localConnId = connectPackage.getLocalConnectionId();
            }

            if (pkg instanceof DefaultDataPackage) {
                DefaultDataPackage defaultDataPackage = (DefaultDataPackage) pkg;
                localConnId = defaultDataPackage.getLocalConnectionId();
            }

            NioSocketChannel socketChannel = connectionManager.getChannel(localConnId);
            if (socketChannel == null || !socketChannel.isOpen()) {
                continue;
            }

            socketChannel.writeAndFlush(Unpooled.wrappedBuffer(Html404.RESP.getBytes()));
            LOG.debug("connId = {}, channel null {}", localConnId, socketChannel == null);

            //remoteSocketChannel.writeAndFlush(pkg.toByteBuf());
        }
    }

    private List<Package> processSendPackages(List<Package> packages) throws Exception {
        return packages;
    }

    private List<Package> processRecvPackages(List<Package> packages) throws Exception {
        return packages;
    }
}
