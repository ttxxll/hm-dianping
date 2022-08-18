package com.hmdp.utils;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * 描述：Redis全局唯一ID生成器
 *
 *  redis的increment每次递增生成一个数值，最大值为2^32次幂，作为全局id低32位，
 *      redis生成数值的key是包含业务和日期的，意味着某个业务在这段时间能能生成2^32次幂个数值，下一个时间周期会重新从0生成数值。
 *
 *  基于20220101-00:00:00的时间戳作为高32位
 *      时间戳得到的是距离锚定点秒数，那么每一秒全局id的高32位都会变化。
 *      全局id的高位拼接上timestamp后，还能避免id具有太明显的规则，防止用户或者说商业对手很容易猜测出来我们的一些敏感信息。
 *      高32位作为时间戳，139年才能用尽。
 *      时间戳作为高32位，increment命令生成的数值作为低32位，意味着每一秒能支持生成2^32个唯一ID。
 *
 *  这样ID整体是递增的，也方便数据库建立索引，方便查询，而且相对于UUID它不是字符型是数值型，存储空间较小。有一定复杂度，不容易看出规律。每天一个key方便做统计
 *
 * @author txl
 * @date 2022-07-28 10:57
 */
@Component
public class RedisIdWorker {

    // 开始的时间戳：2022-01-01 00:00:00这个时刻的时间戳
    private static final long BEGIN_TIMESTAMP = 1640995200L;

    // 左移位数
    private static final int COUNT_BITS = 32;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 生成唯一全局id：
     *  redis的increment每次递增生成一个数值，最大值为2^32次幂，作为全局id低32位，
     *      我们用redis的increment命令生成的数值对应的key是包含业务字符和当前日期的，而且increment命令得到的数值上限是2^32次幂
     *      那么意味着不同业务每一天的key都是不同，都会重新生成数值。即对某一天的某个业务来说，redis能够生成2^32个数值，这对于一天来说肯定够用了。
     *      也可以把粒度做的更细，如果key中的日期部分是精确到小时的，那么意味着某个业务一个小时内redis能生成2^32个数值。
     *      数值只是全局id的一部分，高32位是当前时刻基于20220101-00:00:00的时间戳，那么这也是动态变化的。
     *  基于20220101-00:00:00的时间戳作为高32位：
     *      时间戳得到的是距离锚定点秒数，那么每一秒全局id的高32位都会变化。
     *      高位拼接上timestamp后，还能避免id具有太明显的规则，防止用户或者说商业对手很容易猜测出来我们的一些敏感信息。
     *      时间戳作为高32位，increment命令生成的数值作为低32位，意味着每一秒能支持生成2^32个唯一ID。
     * @param keyPrefix key前缀
     * @return
     */
    public long nextId(String keyPrefix) {

        // 1.生成时间戳：基于20220101-00:00:00的时间戳
        LocalDateTime now = LocalDateTime.now();
        long nowSecond = now.toEpochSecond(ZoneOffset.UTC);
        long timestamp = nowSecond - BEGIN_TIMESTAMP;

        // 2.生成序列号
        // 2.1.获取当前日期，精确到天
        String date = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        // 2.2.数值部分：如果key不存在，会自动创建，所以不会有空指针
        long count = stringRedisTemplate.opsForValue().increment("icr:" + keyPrefix + ":" + date);

        // 3.拼接并返回
        /**
         *  1.假设timestamp = 1L，注意timestamp是64位的long类型，左移32位
         *                                          0000 0000 0000 0000 0000 0000 0000 0001 << 32
         *  0000 0000 0000 0000 0000 0000 0000 0001 0000 0000 0000 0000 0000 0000 0000 0000
         *
         *  2.再做或运算，假设count是65535，count也是long类型
         *                                          0000 0000 0000 0000 1111 1111 1111 1111
         *  0000 0000 0000 0000 0000 0000 0000 0001 0000 0000 0000 0000 0000 0000 0000 0000
         *
         *  3.结果：
         *  0000 0000 0000 0000 0000 0000 0000 0001 0000 0000 0000 0000 1111 1111 1111 1111
         *
         *  4.为什么是或运算：
         *      timestamp左移32位后，低32位都是0。increment命令得到的数值上限是2^32次幂。
         *      两者做为运算时，相当于将increment数值直接拼接在timestamp的低32位，最后得到的结果就是全局唯一ID。
         */
        return timestamp << COUNT_BITS | count;
    }

    public static void main(String[] args) {

        // 获得2022-01-01 00:00:00这个时刻的时间戳
        LocalDateTime timestamp = LocalDateTime.of(2022, 1, 1, 0, 0, 0);
        long second = timestamp.toEpochSecond(ZoneOffset.UTC);
        System.out.println(second);

        // 18024433L：基于2022-01-01 00:00:00这个时刻的时间戳是18024433L，转换成具体的时间格式
        long count = second + 18024433L;
        LocalDateTime localDateTime = LocalDateTime.ofEpochSecond(count, 0, ZoneOffset.UTC);
        String dataStr = localDateTime.format(DateTimeFormatter.ofPattern("yyyy:MM:dd hh:mm:ss"));
        System.out.println(dataStr);

        // 0001 0000 0000 0000 0000 0000 0000 0000 0000
        long num = 1L << COUNT_BITS;
        System.out.println(num);

        // 目前距离2022-07-28 15:55:00多少秒
        LocalDateTime timestamp1 = LocalDateTime.of(2022, 7, 28, 15, 55, 0);
        long second1 = timestamp1.toEpochSecond(ZoneOffset.UTC);
        LocalDateTime now = LocalDateTime.now();
        long nowSecond = now.toEpochSecond(ZoneOffset.UTC);
        System.out.println(nowSecond - second1);
    }
}
