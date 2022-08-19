package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.constant.RedisConstants;
import com.hmdp.constant.RespConstant;
import com.hmdp.constant.SystemConstants;
import com.hmdp.dto.Result;
import com.hmdp.entity.RedisData;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.hmdp.utils.CacheClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.geo.*;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.Function;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private CacheClient cacheClient;

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    /**
     * 用setnx模拟互斥锁：如果多个线程都赖执行setnx，那么只有一个线程会返回成功。返回成功就等价于抢到锁。
     * 加上过期时间，避免出现系统异常导致没有释放锁。
     * @param key
     * @return
     */
    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 1000, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    // 解锁
    private void  unlock(String key) {
        stringRedisTemplate.delete(key);
    }

    @Override
    public Result queryById(Long id) {

        // 1.1.解决缓存穿透的逻辑
        // queryWithPassThrough(id);

        Function<Long, Shop> function = new Function<Long, Shop>() {
            @Override
            public Shop apply(Long shopID) {
                return getById(shopID);
            }
        };
        // 1.2.封装的redis工具类中：缓存空值解决缓存击穿
        // Shop shop = cacheClient.queryWithPassThrough(RedisConstants.CACHE_SHOP_KEY, id, Shop.class, (shopId) -> getById(shopId), RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);

        // 2.1.用互斥锁解决缓存击穿
        // Shop shop = queryWithMutex(id);

        // 2.2.逻辑过期解决缓存击穿：先向redis中存入一些带有逻辑过期属性的热点数据
        // Shop shop = queryWithLogicExpire(id);

        // 2.3.封装的redis工具类中：逻辑过期解决缓存击穿：先向redis中存入一些带有逻辑过期属性的热点数据
        Shop shop = cacheClient.queryWithLogicExpire(RedisConstants.CACHE_SHOP_KEY, id, Shop.class,
                (shopId) -> getById(shopId), 10L, TimeUnit.SECONDS);

        if (shop == null) {
            return Result.fail(RespConstant.MESSAGE_SHOP_NO_EXIST);
        }
        return Result.ok(shop);
    }

    /**
     * 逻辑过期解决缓存击穿：没有TTL，但有一个逻辑过期属性
     * 测试：
     *  我们先提前向redis中放一个快过期的数据，然后redis中的是过期数据，且过期数据和数据库中的数据不一致。
     *  这个时候多线程访问的话会出现短期的数据不一致的情况，直到一个线程将redis更新好。
     * @param id
     * @return
     */
    public Shop queryWithLogicExpire(Long id) {
        // 1.先从redis查询商户：这里演示一下用string类型存储shop对象
        String key = RedisConstants.CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);

        // 2.空值都意味着数据库不存在该商户
        if ("".equals(shopJson)) {
            return null;
        }

        /**
         * 如果是null，我这里还是要用一个异步线程去重建缓存的话，但是我要返回一个Shop，那我怎么拿到异步线程的结果呢？Callable
         *
         * 或者说按照课程的逻辑：如果是null直接返回null。因为假设的是这样的场景，热点数据先调用某个方法（saveShop2Redis）缓存到redis中了，
         * 而且因为只有逻辑过期，没有设置TTL，所以热点数据肯定一直在redis中的。那么如果redis查询是null意味着还没有调用saveShop2Redis缓存到redis中了，
         * 即还没有开始这个活动。
         */
        if (null == shopJson) {
            // 缓存重建：当初始redis中的数据是null时，会有多个线程走到这个方法，但是只有一个线程能抢到锁，所以其他线程还是会返回null。
            // 所以正常情况下先让redis有数据来测试：
            return rebuildShopCache(id, key);
        }

        // 3.命中判断过期时间
        RedisData shopRedisData = JSONUtil.toBean(shopJson, RedisData.class);
        // Shop shop = BeanUtil.copyProperties(shopRedisData.getData(), Shop.class);
        // 体会一下这种写法：这里因为shopRedisData.getData()得到的是Object，但实际上是经过反序列化的JSONObject。
        JSONObject data = (JSONObject) shopRedisData.getData();
        Shop shop = JSONUtil.toBean(data, Shop.class);
        if (shopRedisData.getExpireTime().isAfter(LocalDateTime.now())) {
            // 3.1.没过期
            return shop;
        }

        // 可能在当前线程刚走到这里判断了缓存数据已过期后，有一个线程执行完了缓存重建逻辑，并释放了锁，缓存有数据了
        // 当前线程能获取锁但是此时不需要再创建缓存了，所以在获取到锁时还要做一次校验重校，即双重校验

        // 4.缓存的数据已过期
        // 4.1.成功获取锁：做缓存重建
        if (tryLock(RedisConstants.LOCK_SHOP_KEY)) {
            try {
                // 4.2.获取到锁后进行双重校验
                RedisData redisData = JSONUtil.toBean(stringRedisTemplate.opsForValue().get(key), RedisData.class);
                if (redisData.getExpireTime().isAfter(LocalDateTime.now())) {
                    unlock(RedisConstants.LOCK_SHOP_KEY);
                    return JSONUtil.toBean((JSONObject) shopRedisData.getData(), Shop.class);
                }
            } catch (Exception e) {
                unlock(RedisConstants.LOCK_SHOP_KEY);
                throw new RuntimeException(e);
            }

            // 4.2.开启异步线程做缓存重建
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    log.debug("Thread = {}, 开始构建shop缓存，id = {}", Thread.currentThread().getName(), 1);
                    Shop shopFromDB = getById(id);
                    if (shopFromDB == null) {
                        // 缓存和数据库都不存在该数据：缓存空值避免缓存穿透
                        stringRedisTemplate.opsForValue().set(key, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
                    } else {
                        Thread.sleep(200L);
                        RedisData newRedisData = new RedisData();
                        newRedisData.setData(shopFromDB);
                        newRedisData.setExpireTime(LocalDateTime.now().plusSeconds(10L));
                        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(newRedisData));
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally{
                    unlock(RedisConstants.LOCK_SHOP_KEY);
                }
            });

        }

        // 5.过期且没获取到锁：说明有其他线程获得了锁，去开启了异步线程做缓存重建。所以当前线程返回旧数据
        return shop;
    }

    // 当初始redis中的数据是null时，会有多个线程走到这个方法，但是只有一个线程能抢到锁，所以其他线程还是会返回null
    private Shop rebuildShopCache(Long id, String key) {
        if (tryLock(RedisConstants.LOCK_SHOP_KEY)) {
            try {
                // 获取到锁后进行双重校验：其他线程缓存了，且还没过期，那么直接返回
                RedisData redisData = null;
                if (stringRedisTemplate.opsForValue().get(key) != null) {
                    redisData = JSONUtil.toBean(stringRedisTemplate.opsForValue().get(key), RedisData.class);
                    if (redisData.getExpireTime().isAfter(LocalDateTime.now())) {
                        return JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);
                    }
                }

                // 开启异步线程做缓存重建
                Callable<Shop> callable = () -> {
                    try {
                        log.debug("Thread = {}, 开始构建shop缓存，id = {}", Thread.currentThread().getName(), 1);
                        Shop shopFromDB = getById(id);
                        if (shopFromDB == null) {
                            // 缓存和数据库都不存在该数据：缓存空值避免缓存穿透
                            stringRedisTemplate.opsForValue().set(key, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
                            return null;
                        } else {
                            Thread.sleep(200L);
                            RedisData newRedisData = new RedisData();
                            newRedisData.setData(shopFromDB);
                            newRedisData.setExpireTime(LocalDateTime.now().plusSeconds(10L));
                            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(newRedisData));
                            return (Shop) newRedisData.getData();
                        }
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    } finally{
                        unlock(RedisConstants.LOCK_SHOP_KEY);
                    }
                };
                // 返回结果
                Future<Shop> future = CACHE_REBUILD_EXECUTOR.submit(callable);
                // 这里回阻塞当前线程 直到一部任务执行结束获得结果。
                return future.get();
            } catch (Exception e) {
                throw new RuntimeException(e);
            } finally{
                unlock(RedisConstants.LOCK_SHOP_KEY);
            }
        }

        // 当初始redis中的数据是null时，会有多个线程走到这个方法，但是只有一个线程能抢到锁，所以其他线程还是会返回null
        return null;
    }

    // 互斥锁解决缓存击穿
    public Shop queryWithMutex(Long id) {
        // 1.先从redis查询商户：这里演示一下用string类型存储shop对象
        String key = RedisConstants.CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);

        // 2.存在直接返回：不为null也不为""
        if (StrUtil.isNotBlank(shopJson)) {
            return JSONUtil.toBean(shopJson, Shop.class);
        }

        // 3.命中空值
        if ("".equals(shopJson)) {
            return null;
        }

        // 4.redis中不存在：开始实现缓存重建。互斥锁实现缓存重建，避免高并发下复杂热点key过期导致的缓存击穿。
        while (null == shopJson) {

            // 可能在当前线程刚进到while此处时，有一个线程执行完了缓存重建逻辑，并释放了锁，此时缓存有数据了。当前线程能获取锁但是此时不需要再创建缓存，所以获取到锁后还要做双重校验。
            // 4.1.获取互斥锁成功
            if (tryLock(RedisConstants.LOCK_SHOP_KEY)) {
                try {
                    // 4.2.双重校验
                    shopJson = stringRedisTemplate.opsForValue().get(key);
                    if (StrUtil.isNotBlank(shopJson)) {
                        return JSONUtil.toBean(shopJson, Shop.class);
                    }

                    // 4.3.重建缓存
                    Shop shop = this.getById(id);
                    // 模拟重建耗时
                    TimeUnit.MILLISECONDS.sleep(500);
                    if (shop == null) {
                        // 缓存和数据库都不存在该数据：缓存空值避免缓存穿透
                        stringRedisTemplate.opsForValue().set(key, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
                        return null;
                    } else {
                        // 存入缓存
                        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop),
                                RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
                        return shop;
                    }
                } catch (InterruptedException e) {
                    e.printStackTrace();
                } finally{
                    // 解锁：只有拿到锁的才能解锁，所以放到这里
                    unlock(RedisConstants.LOCK_SHOP_KEY);
                }
            } else {
                try {
                    // 4.4.没有获取到锁，休眠一段时间，再重试查询
                    TimeUnit.MILLISECONDS.sleep(100);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                // 4.5.重试从缓存获取（休眠期间其他线程可能完成了缓存重建）
                shopJson = stringRedisTemplate.opsForValue().get(key);
            }
        }

        // 5.shopJson不为null，意味着缓存命中，会走到此处。
        return JSONUtil.toBean(shopJson, Shop.class);
    }

    // 缓存空值解决缓存穿透的逻辑
    public Shop queryWithPassThrough(Long id) {
        // 1.先从redis查询商户：这里演示一下用string类型存储shop对象
        String key = RedisConstants.CACHE_SHOP_KEY + id;
        //Map<Object, Object> shopMap = stringRedisTemplate.opsForHash().entries(key);
        String shopJson = stringRedisTemplate.opsForValue().get(key);

        // 2.存在直接返回
        if (StrUtil.isNotBlank(shopJson)) {
            return JSONUtil.toBean(shopJson, Shop.class);
        }

        // 3.命中空值
        if ("".equals(shopJson)) {
            return null;
        }

        // 4.redis中不存在，查询数据库
        Shop shop = this.getById(id);
        if (shop == null) {
            // 4.1.将空值写到redis，避免缓存穿透：这是有数据不一致的情况的，如果这个id的商户在之后生成了，但是缓存中的空值还没过期，
            // 那么就会出现命中空值提示数据不存在，但是数据库中有这个数据。
            stringRedisTemplate.opsForValue().set(key, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }

        // 5.存入缓存
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop),
                RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);

        return shop;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result update(Shop shop) {
        Long id = shop.getId();
        if (id == null) {
            return Result.fail(RespConstant.MESSAGE_MISSING_SHOPID);
        }

        // 1.更新数据库
        this.updateById(shop);

        // 2.删除缓存
        stringRedisTemplate.delete(RedisConstants.CACHE_SHOP_KEY + id);

        return Result.ok();
    }

    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {

        // 1.判断是否需要根据坐标查询
        if (x == null || y == null) {
            // 不需要要按坐标查询，直接从数据库查询
            // 根据类型分页查询
            Page<Shop> page = query()
                    .eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            // 返回数据
            return Result.ok(page.getRecords());
        }

        // 2.计算分页参数
        int from = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        int end = current * SystemConstants.DEFAULT_PAGE_SIZE; // 假如current是3，那么end是15。下面会查出15条记录，然后在刷选出from~15的分页数据

        // 3.查询redis，按照距离排序，分页。
        // redis 6：GEOSEARCH key BYLONLAT x y BYRADIUS 10 WITHDISTANCE
        String key = RedisConstants.SHOP_GEO_KEY + typeId;
//        GeoResults<RedisGeoCommands.GeoLocation<String>> results1 = stringRedisTemplate.opsForGeo()
//                .search(
//                        key,
//                        GeoReference.fromCoordinate(x, y),
//                        new Distance(10000), // 默认是米
//                        RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().limit(end));
        // redis 5：GEORADIUS g1 116.397904 39.909005 10 km withdist
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo().radius(
                key,
                new Circle(new Point(x, y), new Distance(10000L)),
                RedisGeoCommands.GeoRadiusCommandArgs.newGeoRadiusArgs().includeDistance().limit(end));

        // 4.解析出id
        if (results == null) {
            return Result.ok(Collections.emptyList());
        }
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = results.getContent();
        if (list.size() <= from) {
            // 没有current页了，结束
            return Result.ok(Collections.emptyList());
        }

        // 4.1.截取from - end 的部分
        ArrayList<Long> ids = new ArrayList<>(list.size());
        HashMap<String, Distance> distanceMap = new HashMap<>(list.size());
        // 从from开始，所以跳过from
        list.stream().skip(from).forEach(result -> {
            // 4.2.获取店铺id
            String shopIdStr = result.getContent().getName();
            ids.add(Long.valueOf(shopIdStr));
            // 4.3.获取距离
            Distance distance = result.getDistance();
            distanceMap.put(shopIdStr, distance);
        });

        // 5.根据id查询shop：注意我们现在整理得到的ids的顺序就是按照距离升序排序过的，所以我们还要保证这个顺序
        String idStr = StrUtil.join(",", ids);
        List<Shop> shops = query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list();
        shops.forEach(
                shop -> shop.setDistance(
                        distanceMap
                                .get(shop.getId().toString())
                                .getValue()));

        // 6.返回
        return Result.ok(shops);
    }

    /**
     * 向redis中存入一些带有逻辑过期属性的热点数据
     * @param id
     * @param expireSeconds
     */
    public void saveShop2Redis(Long id, Long expireSeconds) {

        // 1.查询店铺数据
        Shop shop = getById(id);

        // 2.封装逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));

        // 3.写入redis
        stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id,
                JSONUtil.toJsonStr(redisData));
    }
}
