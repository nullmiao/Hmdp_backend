package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IVoucherService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {
@Autowired
private ISeckillVoucherService seckillVoucherService;
    @Autowired
    private RedisIdWorker redisIdWorker;
@Autowired
    private StringRedisTemplate stringRedisTemplate;
@Autowired
private RedissonClient redisson;
    @Override
    @Transactional
    public Result seckillVoucher(Long voucherId) {
        //查询优惠券信息
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        //判断秒杀是否开始
        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
            return Result.fail("秒杀尚未开始");
        }
        //判断秒杀是否结束
        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
            return Result.fail("秒杀已经结束");
        }
        //判断库存是否充足
        if (voucher.getStock() < 1) {
            return Result.fail("库存不足");
        }
        //扣减库存

        /// 我们用乐观锁来实现扣减库存，更新时判断库存是否大于0且未被修改过，如果更新失败说明库存不足或者被其他线程修改过了

        /// 这个锁的粒度是用户级别的，也就是说同一个用户的订单会被串行化处理，不同用户的订单可以并行处理，这样就避免了全局锁带来的性能问题，同时也保证了同一个用户只能购买一次
        /// intern()方法会返回字符串常量池中的字符串对象，如果字符串已经存在于常量池中，则返回该对象的引用；如果字符串不存在于常量池中，则将该字符串添加到常量池中，并返回新对象的引用。这样就保证了同一个字符串字面量在内存中只有一个实例，从而实现了锁的共享和互斥。
        Long userId = UserHolder.getUser().getId();
       // SimpleRedisLock simpleRedisLock = new SimpleRedisLock(stringRedisTemplate, "order" + userId);
        RLock lock = redisson.getLock("order" + userId);


        boolean tryLock = lock.tryLock();
         if (!tryLock) {
            return Result.fail("不允许重复下单");
        }
        try{     /// 如果不获取代理对象的话，事务注解就不会生效，因为事务是通过AOP实现的，而AOP是基于代理模式的，如果直接调用方法的话，就相当于绕过了代理对象，事务就不会生效了，所以我们需要获取当前对象的代理对象来调用方法，这样才能保证事务注解生效。
            IVoucherOrderService o = (IVoucherOrderService) AopContext.currentProxy();
            return o.createVoucherOrder(voucherId, userId);
        }finally{
            lock.unlock();
        }

    }
@Transactional
    public Result  createVoucherOrder(Long voucherId,Long userId) {
    //实现一人一单的判断
    //先查询订单表中是否存在当前用户的订单，如果存在说明已经购买过了

    Integer count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
    if (count > 0) {
            return Result.fail("你已经购买过了");
        }
        boolean success = seckillVoucherService.update().setSql("stock = stock - 1")
                .eq("voucher_id", voucherId)//eq("stock", voucher.getStock()),这种乐观锁失败率太高了，因为在查询和更新之间可能有其他线程修改了库存，所以我们直接在更新时判断库存是否大于0，这样就避免了这个问题
                .gt("stock", 0).update();
        if (!success) {
            return Result.fail("库存不足");
        }
        //创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);

        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);
        save(voucherOrder);
        return Result.ok(orderId);

 }
}
