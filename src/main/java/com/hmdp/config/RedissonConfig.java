package com.hmdp.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 描述：
 *
 * @author txl
 * @date 2022-07-31 22:54
 */
@Configuration
public class RedissonConfig {

    @Bean
    public RedissonClient redissonClient() {

        // 配置
        Config config = new Config();
        config.useSingleServer()
                .setAddress("redis://127.0.0.1:6379")
                .setPassword("root");

        // 创建RedissonClient对想象
        return Redisson.create(config);
    }

//    /**
//     * 多个Redis节点演示Redisson的MutiLock
//     * @return
//     */
//    @Bean
//    public RedissonClient redissonClient2() {
//
//        // 配置
//        Config config = new Config();
//        config.useSingleServer()
//                .setAddress("redis://xxx.xxx.xxx.xxx:xxxx")
//                .setPassword("root");
//
//        // 创建RedissonClient对想象
//        return Redisson.create(config);
//    }
//
//    @Bean
//    public RedissonClient redissonClient3() {
//
//        // 配置
//        Config config = new Config();
//        config.useSingleServer()
//                .setAddress("redis://xxx.xxx.xxx.xxx:xxxx")
//                .setPassword("root");
//
//        // 创建RedissonClient对想象
//        return Redisson.create(config);
//    }
}
