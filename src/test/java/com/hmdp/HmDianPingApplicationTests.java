package com.hmdp;

import com.hmdp.constant.RedisConstants;
import com.hmdp.entity.Shop;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootVersion;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

@SpringBootTest
class HmDianPingApplicationTests {

    @Resource
    private ShopServiceImpl shopService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private ExecutorService executorService = Executors.newFixedThreadPool(500);

    @Test
    void test() {
        String version = SpringBootVersion.getVersion();
        System.out.println("version = " + version);
    }

    @Test
    void testSaveShop2Redis() {
        shopService.saveShop2Redis(10L, 10L);
    }

    // 并发生成3w个id
    @Test
    void testIdWorker() throws InterruptedException {

        CountDownLatch latch = new CountDownLatch(300);

        Runnable task = () -> {
            for(int i = 0; i < 100; i++) {
                long id = redisIdWorker.nextId("order");
                System.out.println("id = " + id);
            }
            latch.countDown();
        };

        long begin = System.currentTimeMillis();
        for (int i = 0; i < 300; i++) {
            executorService.submit(task);
        }

        latch.await();
        long end = System.currentTimeMillis();
        System.out.println("time = " + (end - begin));
    }


    @Test
    void loadShopData() {
        // 1.查询店铺信息
        List<Shop> list = shopService.list();

        // 2.把店铺按照typeId分组
        Map<Long, List<Shop>> map = list.stream().collect(Collectors.groupingBy(Shop::getTypeId));

        // 3.写入redis
        for (Map.Entry<Long, List<Shop>> entry : map.entrySet()) {
            // 3.1.获取类型id
            Long typeId = entry.getKey();
            // 3.2.获取同类型的店铺集合
            List<Shop> value = entry.getValue();
            // 3.3.写入redis GEOADD key 经度 纬度 member
            String key = RedisConstants.SHOP_GEO_KEY + typeId;

            // 3.4.构造一个locations：
            List<RedisGeoCommands.GeoLocation<String>> locations = new ArrayList<>();
            for (Shop shop : value) {
                // 一个一个添加
                // stringRedisTemplate.opsForGeo().add(key, new Point(shop.getX(), shop.getY()), shop.getId().toString());
                locations.add(new RedisGeoCommands.GeoLocation<>(shop.getId().toString(), new Point(shop.getX(), shop.getY())));
            }
            // 3.5.批量添加
            stringRedisTemplate.opsForGeo().add(key, locations);
        }
    }
}
