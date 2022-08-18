package com.hmdp.redisson;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.boot.test.context.SpringBootTest;

import javax.annotation.Resource;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * 描述：
 *
 * @author txl
 * @date 2022-08-01 20:47
 */
@Slf4j
@SpringBootTest
public class RedissonTest {

    @Resource
    private RedissonClient redissonClient;

    @Resource
    private RedissonClient redissonClient1;

    @Resource
    private RedissonClient redissonClient2;

    private RLock lock;

    @BeforeEach
    void setUp() {
        lock = redissonClient.getLock("order");
    }

//    /**
//     * 演示获取多节点Redis模式 连锁MutiLock
//     */
//    @BeforeEach
//    void setUp() {
//        RLock lock1 = redissonClient.getLock("order");
//        RLock lock2 = redissonClient1.getLock("order");
//        RLock lock3 = redissonClient2.getLock("order");
//
//        // 创建Redisson连锁 MutiLock
//        lock = redissonClient.getMultiLock(lock1, lock2, lock3);
//    }

    @Test
    void method1() {
        // 默认的TTL是30s
        boolean isLock = lock.tryLock();
        // 指定了waitTime重试时间，会在该时间内重试获取锁（订阅到其他线程释放锁的消息时）
        // boolean isLock = lock.tryLock(1, 10, TimeUnit.SECONDS);
        // 不指定leaseTime，会递归调用一个定时任务为其重置TTL有效期
        // boolean isLock = lock.tryLock(1, TimeUnit.SECONDS);
        if (!isLock) {
            log.error("获取锁失败 ... 1");
            return;
        }
        try {
            log.info("获取锁成功 ... 1");
            method2();
            log.info("开始执行业务 ... 1");
        } finally{
            log.warn("准备释放锁 .... 1");
            lock.unlock();
        }
    }

    private void method2() {
        boolean isLock = lock.tryLock();
        if (!isLock) {
            log.error("获取锁失败 ... 2");
            return;
        }
        try {
            log.info("获取锁成功 ... 2");
            log.info("开始执行业务 ... 2");
        } finally{
            log.warn("准备释放锁 .... 2");
            lock.unlock();
        }
    }

    @Test
    void checkExpire() throws ParseException {

        String expireStr = "20220804";
        String pattern = "yyyyMMdd";
        SimpleDateFormat format = new SimpleDateFormat(pattern);
        Date nowDate = new Date(System.currentTimeMillis());
        nowDate = format.parse(format.format(nowDate));
        Date date = format.parse(expireStr);
        System.out.println(date.before(nowDate));
    }

    @Test
    void test2() {
        System.out.println(5 / 2);
    }

}
