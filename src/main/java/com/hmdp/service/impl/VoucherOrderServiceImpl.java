package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollectionUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import com.hmdp.utils.lock.SimpleRedisLock;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;


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
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Autowired
    private ISeckillVoucherService seckillVoucherService;

    @Autowired
    private RedisIdWorker redisIdWorker;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    // redis分布式工具
    @Autowired
    private RedissonClient redissonClient;

    /**
     * 单体服务的互斥锁实现一人只能秒杀一单，然后生成订单，乐观锁实现库存扣减。
     * @param voucherId
     * @return
     */
    @Override
    public Result seckillVoucher(Long voucherId) {

        // 1.查询优惠券
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);

        // 2.判断秒杀是否开始
        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
            return Result.fail("秒杀尚未开始");
        }

        // 3.判断秒杀是否已经结束
        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
            return Result.fail("秒杀活动已经结束");
        }

        // 4.库存是否充足
        if (voucher.getStock() < 1) {
            return Result.fail("库存不足");
        }

        /**
         * 1.一人一单：
         *  int count = where userId = ? and voucherId = ? 当count > 0，就不允许再秒杀优惠券了。
         *  简单的加上这个逻辑还是存在并发问题。如果这个用户同时打过了多个线程请求，那么可能会存在某时刻多个线程判断都不存在优惠券，就都去执行扣减库存的操作。
         *  我们需要实现的一个用户只能下一单，如果一个用户短时间打了多个并发请求，我们要让其只能下单成功一次。
         *  那么就要让每个用户同一时刻只能有一个线程在执行这一系列逻辑：查询订单表是否已经下了订单，没下订单的话就下订单，下了订单就退出。
         *  因为不是更新数据，这个时候用悲观锁较合适
         *
         * 2.锁的粒度：
         *  加到方法上，这是一个对象方法，所以相当于锁这个Service对象。那么每个线程进来都会被锁住。
         *  加到用户id上：因为我们要让每个用户同一时刻只能有一个线程在执行这个逻辑：查询订单表是否已经下了订单，没下订单的话就下订单，下了订单就退出。
         *
         * 3.怎么保证userID是同一个对象？
         *  3.1.首先在拦截器里面，每次往线程ThreadLocal中存入的user都是一个新创建的对象，所以直接锁user对象的话不行，因为每个请求会创建新对象。
         *  3.2.Long userId：
         *      这个好像是相同的，是因为包装类的缓存，Long类型会先缓存一个Long类型的Cache数组，且范围在[-128, 127]，这个范围的数值会直接返回Long类缓存好的对象。
         *      类似Integer的IntegerCache，在[-128, 127]数值范围内会直接返回缓存好了的对象。
         *      所以也不能使用Long userId，因为它只是在[-128, 127]内能保证数值一样的用户id都是同一个对象，超过这个范围即使数值一样但也是不同的Long对象
         *  3.3.userId.toString()：每次返回的都是new的新对象
         *  3.4.userId.toString().intern()：
         *      intern()是一个本地native方法，其核心逻辑是当intern()被调用时，会判断字符串常量池中是否存在与该String对象相同的字符串，注意判断的方法是equal判断的是字符串。
         *      如果有的话就返回字符串在常量池中的引用，没有的话会将该字符串对象的引用在字符串常量池放一份，并返回该引用。
         *      主要是为了避免在堆中再创建一个String对象，而且intern()返回的一定是字符串常量池中的常量/引用
         *
         * 4.锁释放了，但是事务还没有提交，也会导致一些并发问题
         *  我们当前方法被Spring的事务控制，而我们在方法内部加锁，此时会发生这种情况：锁已经释放了，但是事务还没有提交。那么当锁已经释放后意味着，其他线程也可以访问这块逻辑了。
         *  当其他线程进行判断该用户是否已经有订单时，因为上一个事务还没提交，就会判断没有下订单，就会走扣减库存生成订单的逻辑。破坏了一人一单的逻辑。
         *  应该在事务提交之后再释放锁。
         *
         * 5.还有一个问题：这里是通过this调用的，即当前的VoucherOrderServiceImpl对象。而不是它的代理对象。
         *  @Transactional 事务要想生效，其实是Spring对当前这个类做了动态代理，拿到了它的代理对象，用代理对象做的动态代理。
         *  而现在我们却指定用的是voucherOrderServiceImpl this对象执行的createVoucherOrder方法，即使这个方法加了@Transactional注解，事务也不会生效，
         *  因为它没有事务功能，只有代理对象才有。要想事务生效，还得利用代理，所以我们要获得事务对象，来操作事务。
         *
         * 6.这个互斥锁只能在单体系统内起到线程互斥的作用：在集群的情况下做不到线程互斥串行执行的效果
         *  JVM内部维护了锁监视器对象，我们当前这个页面的锁监视器是常量池中的userId。
         *  在集群的情况下，一个新的部署就意味着这是一个新的JVM进程，它有自己的堆栈方法区常量池。
         *  不同JVM进程肯定是相互独立隔离的，那么它维护的锁监视器对象肯定和其他JVM进程不一样，所以只能所指同一个JVM进程中的线程。
         */
        Long userId = UserHolder.getUser().getId();
        synchronized (userId.toString().intern()) {
            // 拿到当前对象的代理对象：为了事务生效
            IVoucherOrderService proxy = (IVoucherOrderService)AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        }
    }


    @Transactional(rollbackFor = Exception.class) // 因为涉及到两张表的修改所以加上事务
    public Result createVoucherOrder(Long voucherId) {

        // 5.一人一单：简单的加上这个逻辑还是存在并发问题。如果这个用户同时打过了多个线程请求，那么可能会存在某时刻多个线程判断都不存在优惠券，就都去执行扣减库存的操作。
        // 所以这个方法要加上互斥锁：让每个用户同一时刻只能有一个线程在执行这一系列逻辑：查询订单表是否已经下了订单，没下订单的话就下订单，下了订单就退出。
        Long userId = UserHolder.getUser().getId();
        int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        if (count > 0) {
            return Result.fail("秒杀卷只能抢购一张");
        }

        /**
         * 5.扣减库存
         *  乐观锁：
         *      将stock作为版本号判断，如果stock不同于之前查询的stock，那么不允许修改。但是这样会造成许多下单失败的情况。
         *      因为同一时刻的当一个线程修改成功后，就会导致其他线程修改失败。（互斥锁是同一时刻只允许一个修改）。
         *  改进一下：
         *      针对这个业务，并不是stock被修改的话，就不允许下单了。应该是stock不足时就不允许修改了。
         *      where id = ? and stock > 0;
         */
        boolean success = seckillVoucherService.update().
                setSql("stock = stock - 1").
                //eq("voucher_id", voucherId).eq("stock", voucher.getStock()).
                eq("voucher_id", voucherId).gt("stock", 0).
                update();

        if (!success) {
            // 扣减失败
            return Result.fail("库存不足");
        }

        // 6.创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        // 6.1.订单id
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);
        // 6.4.写入数据库
        save(voucherOrder);

        return Result.ok(voucherOrder.getId());
    }

    /**
     * 分布式互斥锁：来实现一人只能秒杀一单，然后生成订单
     * @param voucherId
     * @return
     */
    @Override
    public Result seckillVoucher1(Long voucherId) {
        // 1.查询优惠券
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);

        // 2.判断秒杀是否开始
        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
            return Result.fail("秒杀尚未开始");
        }

        // 3.判断秒杀是否已经结束
        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
            return Result.fail("秒杀活动已经结束");
        }

        // 4.库存是否充足
        if (voucher.getStock() < 1) {
            return Result.fail("库存不足");
        }

        /**
         * 创建锁对象：
         * 1.针对的是每个用户只能下一单，所以我们用userId来作为key。当这个用户同时发起多个请求来秒杀下单时，那么都会去尝试执行setnx userid value
         *  但是只有一个能成功。锁定的范围只是单独一个用户，粒度较小，不会影响其他用户，并发性能好。
         * 2.因为我们的业务是一人一单，所以如果抢锁失败了，说明该用户在重复下单，就直接返回失败。
         * 3.比较一下
         *  用sync互斥锁：
         *      如果抢锁失败后，我们无法控制线程。线程会阻塞BLOCKED，然后重试。等待持有锁的线程释放锁。释放锁后继续抢占，
         *      抢占到后进入方法判断发现count > 0，已经下过单了，才会提示秒杀卷只能抢购一张。
         *  分布式锁：
         *      抢锁失败后，我们可以按照业务逻辑来判断是让线程阻塞重试还是直接停止返回失败，更加灵活。
         *      但是相较于sync写法叫复杂，尤其是要实现一个阻塞重试的加锁解锁逻辑时。
         */
        Long userId = UserHolder.getUser().getId();
        SimpleRedisLock simpleRedisLock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
        boolean isLock = simpleRedisLock.tryLock(1200L);// 这里给那么多的超时时间是为了方便后面打断点测试：实际上一般业务如果是500ms，给5s就很多了。
        if (!isLock) {
            // 因为我们的业务是一人一单，所以如果抢锁失败了，就直接返回失败
            return Result.fail("不允许重复下单");
        }

        try {
            // 拿到当前对象的代理对象：为了事务生效
            IVoucherOrderService proxy = (IVoucherOrderService)AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        } finally{
            simpleRedisLock.unlock();
        }
    }

    /**
     * 分布式互斥锁：Redisson实现分布式锁，具有可重入，重试，超时释放等功能
     * @param voucherId
     * @return
     */
    public Result seckillVoucher2(Long voucherId) {
        // 1.查询优惠券
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);

        // 2.判断秒杀是否开始
        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
            return Result.fail("秒杀尚未开始");
        }

        // 3.判断秒杀是否已经结束
        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
            return Result.fail("秒杀活动已经结束");
        }

        // 4.库存是否充足
        if (voucher.getStock() < 1) {
            return Result.fail("库存不足");
        }

        Long userId = UserHolder.getUser().getId();

        // redisson提供的分布式锁：指定锁名称
        RLock lock = redissonClient.getLock("lock:order:" + userId);

        // 获取锁，参数分别是：获取锁的最大等待时间（期间会重试，超过这个时间还没有获取到返回false），锁自动释放时间，时间单位
        // 默认参数是：-1，30，SECONDS。-1表示失败了不重试，直接返回，默认的TTL是30s
        boolean isLock = false;
        try {
            isLock = lock.tryLock(1, 10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            throw new RuntimeException("下单失败");
        }

        if (!isLock) {
            // 因为我们的业务是一人一单，所以如果抢锁失败了，就直接返回失败
            return Result.fail("不允许重复下单");
        }
        try {
            // 拿到当前对象的代理对象：为了事务生效
            IVoucherOrderService proxy = (IVoucherOrderService)AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        } finally{
            lock.unlock();
        }
    }

    // 执行lua脚本seckill.lua
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    // 阻塞队列：如果从队列中取元素时发现没有元素，会阻塞当前线程
    private BlockingQueue<VoucherOrder> orderTaskQueue = new ArrayBlockingQueue<>(1024 * 1024);

    /**
     * 线程池：
     *  这里用了一个单线程的线程池，让他慢慢处理数据库的下单。因为只提交了一个任务，任务循环的从阻塞队列中拿任务。
     *  实际上用这个线程池肯定不好，处理太慢，容易造成任务堆积，阻塞队列变得很大，可能出现GC
     */
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    // 当前类的代理对象
    private IVoucherOrderService proxy;

    /**
     * 当前类初始化后就执行该方法：
     *  向线程池中提交了一个任务，循环的从阻塞队列中拿任务做处理。
     */
    @PostConstruct
    private void init() {
        // 这里注释掉是方面下面的任务能成功提交，因为我们这个线程池是newSingleThreadExecutor。同时只能处理一个任务
        // SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    private class VoucherOrderHandler implements Runnable {
        @Override
        public void run() {
            while (true) {
                try {
                    // 1.获取队列中的订单信息：如果没有会阻塞当前线程
                    VoucherOrder voucherOrder = orderTaskQueue.take();
                    // 2.创建订单
                    handleVoucherOrder(voucherOrder);
                } catch (Exception e) {
                    log.error("异步处理订单异常：{}", e.getMessage());
                }
            }
        }
    }

    /**
     * 优化秒杀：异步秒杀，JDK自带阻塞队列实现异步解耦处理
     *  如果redis中没有优惠券的库存，和存储哪些用户购买过这个优惠券的set的话 要先向redis中添加库存个数据
     *  set seckill:stock:voucherId 200
     *  set集合不需要专门添加，会在sadd添加元素是创建
     * @param voucherId
     * @return
     */
    @Override
    public Result seckillVoucher3(Long voucherId) {
        // 获取用户Id
        Long userId = UserHolder.getUser().getId();

        // 1.执行lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString());

        // 2.判断结果
        int r = result.intValue();
        if (r == 1) {
            return Result.fail("库存不足");
        }
        if (r == 2) {
            return Result.fail("不能重复下单");
        }

        // 3.为0，有购买资格，把下单信息保存到阻塞队列
        long orderId = redisIdWorker.nextId("order");
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);
        orderTaskQueue.add(voucherOrder);

        // 在主线程中获取当前类的代理对象
        proxy = (IVoucherOrderService)AopContext.currentProxy();

        // 4.返回订单id
        return Result.ok(orderId);

    }

    // 这里是异步处理动作，不用返回给前端
    private void handleVoucherOrder(VoucherOrder voucherOrder) throws InterruptedException {

        // redisson提供的分布式锁：指定锁名称
        // 注意这里的userId不能再从UserHolder中取了，因为执行这个方法的是主线程开启的异步线程。而只有发起的网络请求才会被拦截器拦截进而将用户存到主线程的ThreadLocal中
        // 加锁只是一个保险的作用，正常情况下这里不加锁也没事，因为前面Lua脚本已经做了并发控制了
        RLock lock = redissonClient.getLock("lock:order:" + voucherOrder.getUserId());

        // 获取锁，参数分别是：获取锁的最大等待时间（期间会重试，超过这个时间还没有获取到返回false），锁自动释放时间，时间单位
        boolean isLock = lock.tryLock(1, 10, TimeUnit.SECONDS);

        if (!isLock) {
            // 因为我们的业务是一人一单，所以如果抢锁失败了，就直接返回失败
            log.error("不允许重复下单");
        }
        try {
            // 拿到当前对象的代理对象：为了事务生效
            // 这里也是拿不到VoucherOrderServiceImpl的代理对象的：因为它是从当前线程的ThreadLocal中拿代理对象。
            // 而执行这段代码的当前线程是我们新开的子线程，没有经过Spring的代理处理，将代理对象存到当前子线程的ThreadLocal中
            // 所以可以先在主线程中获取然后传给子线程。
            proxy.createVoucherOrder1(voucherOrder);
        } finally{
            lock.unlock();
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void createVoucherOrder1(VoucherOrder voucherOrder) {

        int count = query()
                .eq("user_id", voucherOrder.getUserId())
                .eq("voucher_id", voucherOrder.getVoucherId())
                .count();
        if (count > 0) {
            log.error("秒杀卷只能抢购一张");
            return;
        }

        // 扣减库存
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherOrder.getVoucherId())
                .gt("stock", 0)
                .update();
        if (!success) {
            // 扣减失败
            log.error("库存不足");
            return;
        }

        // 订单写入数据库
        save(voucherOrder);
    }


    @PostConstruct
    private void init1() {
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler1());
        log.info("提交任务，开始循环从Stream消息队列中获取消息！");
    }

    /**
     * 处理Redis Stream消息队列中的消息
     */
    private class VoucherOrderHandler1 implements Runnable {
        @Override
        public void run() {
            int i = 1;
            while (true) {
                // log.info("{} 第{}次尝试获取消息", Thread.currentThread().getName(), i++);
                try {
                    /**
                     * 1.获取消息队列中的订单消息：在消费者组中创建一个消费者1读消息，应该是每个服务节点对应一个消费者，一般配置在yaml中
                     *  相应的命令：XREADGROUP GROUP group1 consumer1 COUNT 1 BLOCK 2000 STREAMS streams.order >
                     *  解释：在消费者组group1用消费者1（没有会创建消费者1）去streams.order队列读消息，从第一个未读的消息开始读，没读到阻塞2s
                     *
                     * 注意：
                     *  我们这里既然是用消费者组来从Stream中读消息，那么每个消费者之间是竞争关系来抢消息。当C1抢到消息1后，就算没有ACK确认，C2也抢不到消息了。
                     *  在这里消费者，我们每次重启服务时创建都是不同的redis-client连接，创建的都是不同的消费者对象。所以如果消费者1消费了2个消息后，但是发生异常了，没ACK确认。
                     *  然后我们重启服务，这次就会创建新的redis客户端连接，新的消费者对象，那么此时他就读取不多之前的两个消息，因为已经被之前的消费者消费过了。
                     */
                    List<MapRecord<String, Object, Object>> messageList = stringRedisTemplate.opsForStream().read(
                            Consumer.from("group1", "consumer1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create("stream.orders", ReadOffset.lastConsumed())
                    );

                    // 2.判断消息获取是否成功
                    if (CollectionUtil.isEmpty(messageList)) {
                        // 2.1.如果获取失败，说明没有消息，继续下一次循环
                        continue;
                    }

                    // 3.如果获取成功，可以下单
                    // MapRecord<S, K, V>：泛型分别是消息的id，消息的key，消息的value
                    // 我们发的消息的格式是key-value，redis最后生成的消息是id-key-value
                    MapRecord<String, Object, Object> record = messageList.get(0);
                    Map<Object, Object> message = record.getValue();
                    VoucherOrder order = BeanUtil.fillBeanWithMap(message, new VoucherOrder(), true);
                    order.setId(Long.parseLong((String)message.get("orderId")));

                    // 3.1.后端做下单处理
                    handleVoucherOrder(order);

                    // 4.ACK确认队列中的pending-list中待确认的消息，刚才消费的信息
                    stringRedisTemplate.opsForStream().acknowledge(
                            "stream.orders",
                            "group1",
                            record.getId());

                    // 成功获取到消息：稍微休眠一下，防战一直抢占CPU
                    Thread.sleep(50);
                } catch (Exception e) {
                    log.error("异步处理订单异常：{}", e.getMessage());
                    // 处理还没来得及确认的消息：在pending-list中的消息
                    handlePendingMessage();
                }
            }
        }

        // 处理pending状态的消息
        private void handlePendingMessage() {
            while (true) {
                try{
                    // 1.获取pending-list中的第一条订单信息：XREADGROUP GROUP group1 consumer1 COUNT 1 STREAMS streams.order 0
                    List<MapRecord<String, Object, Object>> messageList = stringRedisTemplate.opsForStream().read(
                            Consumer.from("group1", "consumer1"),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create("stream.orders", ReadOffset.from("0"))
                    );

                    // 2.判断消息获取是否成功
                    if (CollectionUtil.isEmpty(messageList)) {
                        // 2.1.如果获取失败，说明没有pending状态的消息，跳出循环
                        break;
                    }

                    // 3.如果获取成功，可以下单
                    // MapRecord<S, K, V>：泛型分别是消息的id，消息的key，消息的value
                    // 我们发的消息的格式是key-value，redis最后生成的消息是id-key-value
                    MapRecord<String, Object, Object> record = messageList.get(0);
                    Map<Object, Object> message = record.getValue();
                    VoucherOrder order = BeanUtil.fillBeanWithMap(message, new VoucherOrder(), true);
                    order.setId(Long.parseLong((String)message.get("orderId")));

                    // 3.1.后端做下单处理
                    handleVoucherOrder(order);

                    // 4.ACK确认队列中的pending-list中待确认的消息，刚才消费的信息
                    stringRedisTemplate.opsForStream().acknowledge(
                            "stream.orders",
                            "group1",
                            record.getId());
                } catch (Exception e) {
                    // 处理pending消息时如果出现异常，会再次进入while循环中，继续处理pending消息：能保证异常情况下的pending订单都能被消费
                    log.error("处理pending-list出现异常", e);
                    try {
                        // 预防如果频繁出现异常
                        Thread.sleep(50);
                    } catch (InterruptedException ex) {
                        ex.printStackTrace();
                    }
                }
            }
        }
    }

    /**
     * 优化秒杀2：异步秒杀，用Redis的Stream实现消息队列。
     *  如果redis中没有优惠券的库存，和存储哪些用户购买过这个优惠券的set的话 要先向redis中添加库存个数据
     *  set seckill:stock:voucherId 200
     *  set集合不需要专门添加，会在sadd添加元素是创建
     *  还要创建休息队列Stream
     * 创建一个消费者组group1，从消息队列stream.orders的第一条消息开始消费。如果队列不存在则创建
     *  XGROUP CREATE stream.orders group1 0 MKSTREAM
     *
     * @param voucherId
     * @return
     */
    @Override
    public Result seckillVoucher4(Long voucherId) {
        // 获取用户Id
        Long userId = UserHolder.getUser().getId();
        // 生成订单id：无论最后订单有没有生成成功，全局id都会生成一次。
        long orderId = redisIdWorker.nextId("order");

        // 1.执行lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.singletonList("stream.orders"),
                voucherId.toString(), userId.toString(), String.valueOf(orderId));

        // 2.判断结果
        int r = result.intValue();
        if (r == 1) {
            return Result.fail("库存不足");
        }
        if (r == 2) {
            return Result.fail("不能重复下单");
        }

        // 在主线程中获取当前类的代理对象
        proxy = (IVoucherOrderService)AopContext.currentProxy();

        // 4.返回订单id
        return Result.ok(orderId);

    }
}
