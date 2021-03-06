package com.villcore.net.proxy.v3.common;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 用来在client与server端生成唯一的connId
 */
public class ConnIdGenerator {
    private AtomicInteger idCount;

    public ConnIdGenerator(int idStart) {
        idCount = new AtomicInteger(idStart);
    }

    /**
     * 自增生成一个唯一connId，该Id只能在该Jvm维持唯一（数值越界不考虑,会自动重置）
     *
     * @return
     */
    public Integer generateConnId() {
        return idCount.getAndIncrement() & Integer.MAX_VALUE;
    }
}
