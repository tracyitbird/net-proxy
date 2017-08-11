package com.villcore.net.proxy.bio.server;

import com.villcore.net.proxy.bio.common.Connection;
import com.villcore.net.proxy.bio.compressor.GZipCompressor;
import com.villcore.net.proxy.bio.crypt.CryptHelper;
import com.villcore.net.proxy.bio.crypt.PasswordManager;
import com.villcore.net.proxy.bio.handler.*;
import com.villcore.net.proxy.bio.util.SocketUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.NoSuchPaddingException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.*;

/**
 * This server do not use squid3 as http proxy, this parse first package from client when connection build,
 * Then parse first package to address and port, and build connection use socket with address and port
 *
 * Created by Administrator on 2017/7/17.
 */
public class Server2 {
    private static final Logger LOG = LoggerFactory.getLogger(Server2.class);

    public static void main(String[] args) throws NoSuchPaddingException, NoSuchAlgorithmException {
        List<Connection> connections = Collections.synchronizedList(new LinkedList<>());
        ExecutorService executorService = new ThreadPoolExecutor(1, 10, 60, TimeUnit.SECONDS, new ArrayBlockingQueue<Runnable>(100));

        int listenPort = 20080;
        InetSocketAddress remoteAddress = new InetSocketAddress("127.0.0.1", 3128);

        ServerSocket serverSocket = null;
        final ServerSocket finalServerSocket = serverSocket;

        try {
            serverSocket = new ServerSocket(listenPort);

            while (true) {
                Socket localSocket = serverSocket.accept();

                Map<String, Handler> handlerMap = new LinkedHashMap<>();
                handlerMap.put("decompress", new DecompressHandler(new GZipCompressor()));
                handlerMap.put("decrypt", new DecryptHandler(new PasswordManager(), new CryptHelper()));
                handlerMap.put("user_to_default", new FromUserPackageHandler());

                executorService.submit(new ClientConnectionDispatcherTask(localSocket, handlerMap, connections));
            }
        } catch (IOException e) {
            LOG.error(e.getMessage(), e);
        }

        Runtime.getRuntime().addShutdownHook(new Thread(){
            @Override
            public void run() {
                if(finalServerSocket != null) {
                    try {
                        finalServerSocket.close();
                    } catch (IOException e) {
                        LOG.error(e.getMessage(), e);
                    }
                }

                for(Connection connection : connections) {
                    connection.stop();
                }
            }
        });
    }
}
