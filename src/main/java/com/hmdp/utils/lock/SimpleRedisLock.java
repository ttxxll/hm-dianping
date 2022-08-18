package com.hmdp.utils.lock;

import cn.hutool.core.lang.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

/**
 * 描述：分布式锁加锁解锁
 *
 * @author txl
 * @date 2022-07-29 23:26
 */
public class SimpleRedisLock implements ILock{

    private static final String KEY_PREFIX = "lock:";

    // 不带-的UUID
    private static final String ID_PREFIX = UUID.randomUUID().toString(true) + "-";

    // Lua脚本：泛型是返回值
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;

    // 初始化Lua脚本
    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }

    // 锁的key：每个业务有不同的锁
    private String key;

    private StringRedisTemplate stringRedisTemplate;

    public SimpleRedisLock(String key, StringRedisTemplate stringRedisTemplate) {
        this.key = key;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean tryLock(long timeoutSec) {
        /**
         * 获取当前线程的id，一个jvm内部每个线程的id一般是不同的：线程id是递增的，每创建一个线程id都会递增
         * 所以一个jvm中线程id能保证唯一，那么在集群多个JVM节点时，每个JVM都维护这样一个递增的线程id，那么就很可能出现线程id相同的情况。
         *
         * 为了防误删分布式锁，每个线程只能删除自己当时获取的分布式做：所以我们为threadId拼上一个uuid，保证其唯一性
         *
         * 因为存在一种极端的情况：
         *  线程1在持有锁的期间出现了阻塞（比如业务执行时间超过了锁的TTL），导致他的锁自动释放。
         *  这时其线程2来尝试获得锁，就拿到了这把锁。然后线程2在持有锁执行过程中，线程1反应过来，继续执行，
         *  走到了删除锁逻辑，此时就会把本应该属于线程2的锁进行删除，这就是误删别人锁的情况说明。
         *  后续会因为线程1一开始持有的锁自动过期释放，然后还删除了线程2的锁，然后线程3也能在线程2释放锁之前抢到锁。
         *
         */
        String threadId = ID_PREFIX + Thread.currentThread().getId();

        // 获取锁
        Boolean success = stringRedisTemplate.opsForValue()
                .setIfAbsent(KEY_PREFIX + key, threadId, timeoutSec, TimeUnit.SECONDS);

        // 自动拆箱可能有空指针风险，当Boolean success是个null对象时，拆箱会报空指针.
        // return success;
        // 这样写的好处：首先Boolean.TRUE是个常量，因为是equals比较所以即使Boolean success是个null时返回的也是false。
        return Boolean.TRUE.equals(success);
    }

    /**
     * 考虑一个极端的情况：
     *  线程1已经走到了释放锁的条件判断过程中，比如他已经判断出当前这把锁确实是属于他自己的，正准备删除锁时，发生了阻塞（比如Full GC会阻塞所有工作线程）。
     *  阻塞过程中他的锁到期了，线程2就可以成功获取到锁，然后线程1他会接着往后执行，当他阻塞结束后，他直接就会执行删除锁那行代码，又导致了锁的误删。
     *  之所以有这个问题，是因为判断锁标识和释放锁之间发生了阻塞，这两个动作不是原子性的。
     */
    public void unlock1() {
        // 如果是当前线程持有的锁才能释放
        String threadId = ID_PREFIX + Thread.currentThread().getId();
        if (threadId.equals(stringRedisTemplate.opsForValue().get(KEY_PREFIX + key))) {
            stringRedisTemplate.delete(KEY_PREFIX + key);
        }

        // 不一致说明锁不是当前线程之前持有的锁，而是另一个线程重新持有的。注意这个key是一样的，为什么会出现这种情况？
        // 因为当前线程没有执行释放锁的动作，那么只可能是因为锁到了TTL过期时间自动释放了。然后后续线程重新获取了锁。
    }

    /**
     * 解决判断锁标识和释放锁，这两个动作不是原子性的：在一个脚本中编写多条Redis命令，确保多条命令执行时的原子性
     */
    @Override
    public void unlock() {
        /**
         * 调用lua脚本：
         *  UNLOCK_SCRIPT：Lua脚本
         *  Collections.singletonList(KEY_PREFIX + key)：KEYS数组
         *  ID_PREFIX + Thread.currentThread().getId()：ARGV数组
         */
        stringRedisTemplate.execute(
                UNLOCK_SCRIPT,
                Collections.singletonList(KEY_PREFIX + key),
                ID_PREFIX + Thread.currentThread().getId());
    }
}
