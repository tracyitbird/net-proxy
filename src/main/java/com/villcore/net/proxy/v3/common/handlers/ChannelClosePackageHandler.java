package com.villcore.net.proxy.v3.common.handlers;

import com.villcore.net.proxy.v3.common.Connection;
import com.villcore.net.proxy.v3.common.PackageHandler;
import com.villcore.net.proxy.v3.common.Tunnel;
import com.villcore.net.proxy.v3.common.TunnelManager;
import com.villcore.net.proxy.v3.pkg.ChannelClosePackage;
import com.villcore.net.proxy.v3.pkg.ConnectReqPackage;
import com.villcore.net.proxy.v3.pkg.Package;
import com.villcore.net.proxy.v3.pkg.PackageType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.stream.Collectors;

/**
 * client/server side package handler
 *
 * 收到ChannelClosePackage，对应关闭掉Tunnel
 */
public class ChannelClosePackageHandler implements PackageHandler {
    private static final Logger LOG = LoggerFactory.getLogger(ChannelClosePackageHandler.class);

    private TunnelManager tunnelManager;

    public ChannelClosePackageHandler(TunnelManager tunnelManager) {
        this.tunnelManager = tunnelManager;
    }

    @Override
    public List<Package> handlePackage(List<Package> packages, Connection connection) {
        List<Package> connectReqPackage = packages.stream()
                .filter(pkg -> pkg.getPkgType() == PackageType.PKG_CHANNEL_CLOSE)
                .collect(Collectors.toList());

        connectReqPackage.stream()
                .map(pkg -> ChannelClosePackage.class.cast(pkg))
                .collect(Collectors.toList())
                .forEach(pkg -> {
                    int corrspondConnId = pkg.getLocalConnId();
                    int connId = pkg.getRemoteConnId();
                    LOG.debug("need handle channel close package ...");
                });

        List<Package> otherPackage = packages.stream().filter(pkg -> pkg.getPkgType() != PackageType.PKG_CHANNEL_CLOSE).collect(Collectors.toList());
        return otherPackage;
    }

    public void closeTunnel(int connId, int corrspondConnId) {
        tunnelManager.tunnelClose(connId);
    }
}
