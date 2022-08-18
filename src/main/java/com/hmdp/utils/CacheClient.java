package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.constant.RedisConstants;
import com.hmdp.entity.RedisData;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * 描述：
 *
 * @author txl
 * @date 2022-07-27 22:31
 */
@Component
@Slf4j
public class CacheClient {

    private final StringRedisTemplate stringRedisTemplate;

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 1000, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    // 解锁
    private void  unlock(String key) {
        stringRedisTemplate.delete(key);
    }

    // 写缓存
    public void set(String key, Object value, Long time, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }

    // 写缓存：逻辑过期属性
    public void setWithLogicExpire(String key, Object value, Long time, TimeUnit unit) {
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    /**
     * @param id id
     * @param keyPrefix 缓存key的前缀
     * @param rClass 具体类型：用来确定泛型
     * @param dbFallback 数据库查询逻辑，参数是ID类型，返回值是R类型
     * @param <R> 定义泛型R
     * @param <ID> 定义泛型ID
     * @return 返回泛型R
     */
    public <R, ID> R queryWithPassThrough(String keyPrefix, ID id, Class<R> rClass,
                                          Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        // 1.先从redis查询商户：这里演示一下用string类型存储shop对象
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);

        // 2.存在直接返回
        if (StrUtil.isNotBlank(json)) {
            return JSONUtil.toBean(json, rClass);
        }

        // 3.命中空值
        if ("".equals(json)) {
            return null;
        }

        /**
         * redis中不存在，查询数据库：
         *  注意这里根本不知道要查的是商户还是用户还是什么，所以不能在这里实现，让调用者把这段逻辑传给我们。
         * 传什么：
         *  我们需要的是一段查询逻辑，或者说查询方法，有参数有返回值的方法，那么用Function
         */
        R r = dbFallback.apply(id);
        if (r == null) {
            // 4.1.将空值写到redis，避免缓存穿透：这是有数据不一致的情况的，如果这个id的商户在之后生成了，但是缓存中的空值还没过期，
            // 那么就会出现命中空值提示数据不存在，但是数据库中有这个数据。
            stringRedisTemplate.opsForValue().set(key, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }

        // 5.存入缓存
        this.set(key, r, time, unit);

        return r;
    }

    public <R, ID> R queryWithLogicExpire(String keyPrefix, ID id, Class<R> rClass,
                                          Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        // 1.先从redis查询商户：这里演示一下用string类型存储shop对象
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);

        // 2.空值都意味着数据库不存在该商户
        if (StrUtil.isBlank(json)) {
            return null;
        }

        // 3.命中判断过期时间
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        JSONObject data = (JSONObject) redisData.getData();
        R r = JSONUtil.toBean(data, rClass);
        if (redisData.getExpireTime().isAfter(LocalDateTime.now())) {
            // 3.1.没过期
            return r;
        }

        // 可能在当前线程刚走到这里判断了缓存数据已过期后，有一个线程执行完了缓存重建逻辑，并释放了锁，缓存有数据了
        // 当前线程能获取锁但是此时不需要再创建缓存了，所以在获取到锁时还要做一次校验重校，即双重校验

        // 4.缓存的数据已过期
        // 4.1.成功获取锁：做缓存重建
        if (tryLock(RedisConstants.LOCK_SHOP_KEY)) {
            try {
                // 4.2.获取到锁后进行双重校验
                redisData = JSONUtil.toBean(stringRedisTemplate.opsForValue().get(key), RedisData.class);
                if (redisData.getExpireTime().isAfter(LocalDateTime.now())) {
                    unlock(RedisConstants.LOCK_SHOP_KEY);
                    return JSONUtil.toBean((JSONObject) redisData.getData(), rClass);
                }
            } catch (Exception e) {
                unlock(RedisConstants.LOCK_SHOP_KEY);
                throw new RuntimeException(e);
            }

            // 4.2.开启异步线程做缓存重建
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    log.debug("Thread = {}, 开始构建shop缓存，id = {}", Thread.currentThread().getName(), 1);
                    R rFromDB = dbFallback.apply(id);
                    if (rFromDB == null) {
                        // 缓存和数据库都不存在该数据：缓存空值避免缓存穿透
                        stringRedisTemplate.opsForValue().set(key, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
                    } else {
                        Thread.sleep(200L); // 模拟复杂key的重建
                        this.setWithLogicExpire(key, rFromDB, time, unit);
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally{
                    unlock(RedisConstants.LOCK_SHOP_KEY);
                }
            });

        }

        // 5.过期且没获取到锁：说明有其他线程获得了锁，去开启了异步线程做缓存重建。返回旧数据
        return r;
    }
}
