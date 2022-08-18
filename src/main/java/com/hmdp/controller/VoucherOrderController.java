package com.hmdp.controller;


import com.hmdp.dto.Result;
import com.hmdp.service.IVoucherOrderService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * <p>
 *  前端控制器
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@RestController
@RequestMapping("/voucher-order")
public class VoucherOrderController {

    @Autowired
    private IVoucherOrderService voucherOrderService;

    /**
     * 单体服务的互斥锁：实现一人只能秒杀一单，然后生成订单，乐观锁实现库存扣减。
     * @param voucherId
     * @return
     */
    @PostMapping("seckill/{id}")
    public Result seckillVoucher(@PathVariable("id") Long voucherId) {
        return voucherOrderService.seckillVoucher(voucherId);
    }

    /**
     * 分布式互斥锁：来实现一人只能秒杀一单，然后生成订单
     * @param voucherId
     * @return
     */
    @PostMapping("seckill1/{id}")
    public Result seckillVoucher1(@PathVariable("id") Long voucherId) {
        return voucherOrderService.seckillVoucher1(voucherId);
    }

    /**
     * 分布式互斥锁：来实现一人只能秒杀一单，然后生成订单
     * @param voucherId
     * @return
     */
    @PostMapping("seckill2/{id}")
    public Result seckillVoucher2(@PathVariable("id") Long voucherId) {
        return voucherOrderService.seckillVoucher2(voucherId);
    }

    /**
     * 优化秒杀：异步秒杀，JDK自带阻塞队列实现异步解耦处理
     * @param voucherId
     * @return
     */
    @PostMapping("seckill3/{id}")
    public Result seckillVoucher3(@PathVariable("id") Long voucherId) {
        return voucherOrderService.seckillVoucher3(voucherId);
    }

    /**
     * 优化秒杀2：异步秒杀，用Redis的Stream实现消息队列。
     * @param voucherId
     * @return
     */
    @PostMapping("seckill4/{id}")
    public Result seckillVoucher4(@PathVariable("id") Long voucherId) {
        return voucherOrderService.seckillVoucher4(voucherId);
    }
}
