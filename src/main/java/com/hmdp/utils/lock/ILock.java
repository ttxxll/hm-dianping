package com.hmdp.utils.lock;

/**
 * @author txl
 * @since 2022-07-29 23:24
 */
public interface ILock {

    /**
     * 尝试获取锁
     * @param timeoutSec 锁超时时间，过期自动释放
     * @return
     */
    boolean tryLock(long timeoutSec);

    /**
     * 释放锁屏
     */
    void unlock();
}
