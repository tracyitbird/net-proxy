package com.villcore.net.proxy.v3.common;

import com.villcore.net.proxy.v3.client.ConnectionRecvPackageGatherHandler;
import com.villcore.net.proxy.v3.pkg.v2.Package;
import com.villcore.net.proxy.v3.pkg.v2.PackageUtils;
import com.villcore.net.proxy.v3.server.UserInfo;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * ConnectionManager
 * <p>
 * <p>
 * 定期关闭空的Connectjion
 */
public class ConnectionManager implements Runnable {
    private static final Logger LOG = LoggerFactory.getLogger(ConnectionManager.class);

    private static final short MAX_RETRY_COUNT = 500;
    private static final long MAX_IDLE_TIME =  30 * 1000L;

    private EventLoopGroup eventLoopGroup;
    private TunnelManager tunnelManager;

    //addr:port -> conn
    private Map<String, Connection> connectionMap = new ConcurrentHashMap<>();
    private Map<String, Short> retryCountMap = new ConcurrentHashMap<>();
    private Object updateLock = new Object();

    private WriteService writeService;

    private Bootstrap bootstrap;

    public ConnectionManager(EventLoopGroup eventLoopGroup, TunnelManager tunnelManager, WriteService writeService) {
        this.eventLoopGroup = eventLoopGroup;
        this.tunnelManager = tunnelManager;
        this.writeService = writeService;

        bootstrap = initBootstrap();
    }

    private Bootstrap initBootstrap() {
        Bootstrap bootstrap = new Bootstrap();
        bootstrap.group(this.eventLoopGroup)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 60 * 1000)
                .option(ChannelOption.SO_KEEPALIVE, true)
                .option(ChannelOption.TCP_NODELAY, true)
                .option(ChannelOption.SO_RCVBUF, 128 * 1024)
                .option(ChannelOption.SO_SNDBUF, 128 * 1024)

//                .option(ChannelOption.ALLOCATOR, UnpooledByteBufAllocator.DEFAULT)
//                .option(ChannelOption.RCVBUF_ALLOCATOR, AdaptiveRecvByteBufAllocator.DEFAULT)

                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) throws Exception {
                        //ch.pipeline().addLast(new ConnectionMessageDecoder());
                        ch.pipeline().addLast(new ConnectionPackageDecoder());
                        ch.pipeline().addLast(new ConnectionRecvPackageGatherHandler(ConnectionManager.this));
                        //ch.pipeline().addLast(new ConnectionMessageEncoder());
                        ch.pipeline().addLast(new ConnectionPackageEncoder());
                    }
                });
        return bootstrap;
    }

    /**
     * server side invoke
     *
     * @param channel
     * @return
     */
    public Connection acceptConnectTo(Channel channel) {
        InetSocketAddress address = (InetSocketAddress) channel.remoteAddress();
        String addr = address.getAddress().getHostAddress();
        int port = address.getPort();
        LOG.debug(">>>>>>>>>>>>>>>>>server accept client connection [{}:{}] ...", addr, port);
        String connectionKey = addrAndPortKey(addr, port);

        synchronized (updateLock) {
            if (connectionMap.containsKey(connectionKey)) {
                Connection connection = connectionMap.get(connectionKey);
                //connection.getRemoteChannel().close();
                connection.setRemoteChannel(channel);
                connection.setConnected(true);
                connection.connectionTouch(System.currentTimeMillis());
                return connection;
            }

            Connection connection = new Connection(address.getHostName(), address.getPort(), tunnelManager);
            connection.setRemoteChannel(channel);
            connection.setConnected(true);
            connection.connectionTouch(System.currentTimeMillis());

            channel.closeFuture().addListener(new GenericFutureListener<Future<? super Void>>() {
                @Override
                public void operationComplete(Future<? super Void> future) throws Exception {
                    if (future.isSuccess()) {
                        connection.setConnected(false);
                        connection.setAuthed(false);
                    }
                }
            });
            connectionMap.put(connectionKey, connection);
            writeService.addWrite(connection);
            return connection;
        }
    }

    /**
     * client side invoke, 客户端调用
     *
     * @param addr
     * @param port
     * @return
     */
    public Connection connectTo(String addr, int port, String username, String password) {
        Connection connection = null;
        synchronized (updateLock) {
            connection = connectionMap.get(addrAndPortKey(addr, port));
            if (connection == null) {
                connection = new Connection(addr, port, tunnelManager);
                connectionMap.putIfAbsent(addrAndPortKey(addr, port), connection);
                tunnelManager.addConnection(connection);
            }

            try {
                Connection finalConnection = connection;

                LOG.debug("ready to connect ...");
                Channel channel = bootstrap.connect(new InetSocketAddress(addr, port), new InetSocketAddress(60070)).sync().addListener(new GenericFutureListener<Future<? super Void>>() {
                    @Override
                    public void operationComplete(Future<? super Void> future) throws Exception {
                        if (future.isSuccess()) {
                            connectSuccess(addr, port, finalConnection, username, password);
                        } else {
                            connectFailed(addr, port, finalConnection, username, password);
                        }
                    }
                }).channel();

                channel.closeFuture().addListener(new GenericFutureListener<Future<? super Void>>() {
                    @Override
                    public void operationComplete(Future<? super Void> future) throws Exception {
                        connectFailed(addr, port, finalConnection, username, password);
                    }
                });
                connection.setRemoteChannel(channel);
                //channel.writeAndFlush(Unpooled.EMPTY_BUFFER);
            } catch (Exception e) {
                LOG.debug(e.getMessage(), e);
                connectFailed(addr, port, connection, username, password);
            }
            return connection;
        }
    }

    private void connectFailed(String addr, int port, Connection finalConnection, String username, String password) {
        //TODO failed
        LOG.debug("connect to remote [{}:{}] server failed...", addr, port);
        finalConnection.setConnected(false);
        finalConnection.setAuthed(false);
        Short curRetry = retryCountMap.getOrDefault(addrAndPortKey(addr, port), Short.valueOf((short) 0));
        LOG.debug("cur retry = {}", curRetry);
        if (curRetry++ < MAX_RETRY_COUNT) {
            retryCountMap.put(addrAndPortKey(addr, port), Short.valueOf(curRetry));
            eventLoopGroup.schedule(new Runnable() {
                @Override
                public void run() {
                    connectTo(addr, port, username, password);
                }
            }, 1000, TimeUnit.MILLISECONDS);
            //connectTo(addr, port);
        } else {
            finalConnection.setConnected(false);
            LOG.debug("retry for [{}] exceed max retry count, this connection will closed...", addrAndPortKey(addr, port));
        }
        writeService.removeWrite(finalConnection);
    }

    private void connectSuccess(String addr, int port, Connection finalConnection, String username, String passowrd) {
        //TODO success
        LOG.debug("connect to remote [{}:{}] server success...", addr, port);
        finalConnection.setConnected(true);

        //finalConnection.getRemoteChannel().config().setAutoRead(false);
        try {
            finalConnection.addSendPackages(Collections.singletonList(PackageUtils.buildConnectAuthReqPackage(username, passowrd)));
            LOG.debug("send auth req ...");
        } catch (Exception e) {
            e.printStackTrace();
        }
        finalConnection.connectionTouch(System.currentTimeMillis());
        retryCountMap.put(addrAndPortKey(addr, port), Short.valueOf((short) 0));
        writeService.addWrite(finalConnection);
        addPingTask(finalConnection);
    }

    private void addPingTask(Connection finalConnection) {
        //TODO 构建ConnectionReqPackage, 添加到Queue中
        eventLoopGroup.schedule(new Runnable() {
            @Override
            public void run() {
                if (finalConnection != null && finalConnection.isConnected()) {
                    Package pkg = PackageUtils.buildChannelClosePackage(-1, -1, -1L);
                    finalConnection.addSendPackages(Collections.singletonList(pkg));

                    addPingTask(finalConnection);
                }
            }
        }, 10, TimeUnit.SECONDS);
    }

    private String addrAndPortKey(String addr, int port) {
        return addr + ":" + String.valueOf(port);
        //return addr;
    }

    public void closeConnection(Connection connection) {
        //发送所有package
        //channel#close
        //state clear
    }

    public Connection channelFor(Channel channel) {
        InetSocketAddress remoteAddr = (InetSocketAddress) channel.remoteAddress();
        String addr = remoteAddr.getAddress().getHostAddress();
        int port = remoteAddr.getPort();
//        LOG.debug("key = {}, channel map = {}", addrAndPortKey(addr, port), connectionMap.toString());

        synchronized (updateLock) {
            return connectionMap.getOrDefault(addrAndPortKey(addr, port), null);
        }
    }

    //TODO need sync
    public List<Connection> allConnected() {
        synchronized (updateLock) {
            return connectionMap.values().stream()
                    .filter(conn -> {
//                        LOG.debug("conn {} ", conn.toString() + conn.isConnected());
                        return conn.isConnected();
                    })
                    .collect(Collectors.toList());
        }
    }

    @Override
    public void run() {
//        自动调度任务，用来清理长时间无响应的Connection
//        过滤touch超时的connection
//        TODO 这个逻辑如果关闭了connection，客户端如何才能新建连接，需要再考虑设计，服务端可以这样使用
//        LOG.debug("scan idle connections ...");
        synchronized (updateLock) {
            List<String> connectionKeys = connectionMap.keySet().stream().collect(Collectors.toList());
            for (String connectionKey : connectionKeys) {
                Connection connection = connectionMap.get(connectionKey);
                if (System.currentTimeMillis() - connection.lastTouch() > MAX_IDLE_TIME) {
                    retryCountMap.remove(connectionKey);
                    if (connection != null) {
                        connection.close();
                        connectionMap.remove(connectionKey);
                        LOG.debug("&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&need close connection [{}] ...", connection);
                    }
                }
            }
        }
    }

    public Connection getConnection(String remoteAddr, int remotePort, String username, String password) {
        String connectionKey = addrAndPortKey(remoteAddr, remotePort);
        synchronized (updateLock) {
            if (connectionMap.containsKey(connectionKey)) {
                //LOG.debug("get a cached connection ...");
                return connectionMap.get(connectionKey);
            }
        }

        //LOG.debug("get a new connection ...");

        // already sync and put into map ...
        Connection connection = connectTo(remoteAddr, remotePort, username, password);
        connection.setUserInfo(new UserInfo(username, password));
        return connection;
    }
}
