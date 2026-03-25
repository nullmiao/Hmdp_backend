package com.hmdp.service.impl;


import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
    @Autowired
    // Inject the proxied service so background thread can call transactional method
    private IVoucherOrderService orderServiceProxy;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static{
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));


        SECKILL_SCRIPT.setResultType(Long.class);
    }
    /// 使用阻塞队列获取信息
//    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024*1024);
    private static final ExecutorService executor = Executors.newSingleThreadExecutor();
    private class VoucherOrderHandle implements Runnable{

//       @Override
//       public void run() {
//while(true){
//    //获取队列中的订单信息
//    try {
//        VoucherOrder take = orderTasks.take();
//        handleVoucherOrder(take);
//    } catch (InterruptedException e) {
//        throw new RuntimeException(e);
//    }
//    //创建订单
//}
//       }

        private void handleVoucherOrder(VoucherOrder take) {

            Long userId = take.getUserId();
            RLock lock = redisson.getLock("order" + userId);

            boolean tryLock = lock.tryLock();
            if (!tryLock) {
                log.error("不允许重复下单");
                return;
            }
            try{
                // Use injected proxy to ensure transactional annotations are applied
                orderServiceProxy.createVoucherOrder(take);
            }finally{
                lock.unlock();
            }
        }
        String queueName="stream.orders";
        @Override
        public void run() {
            while(true){
                //获取队列中的订单信息
                try {
                    //获取消息队列中的信息 XREADGROUP GROUP g1 c1 count1 1 block 2000 STREAMS streams.order 0
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(queueName, ReadOffset.lastConsumed())
                    );
                    if (list==null||list.size()==0) {
                        // 没有读取到消息，继续循环等待（不要退出线程）
                        continue;
                    }
                    MapRecord<String, Object, Object> record = list.get(0);
                    RecordId id = record.getId();
                    Map<Object, Object> value = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                    handleVoucherOrder(voucherOrder);
                    /// ack确认
                    stringRedisTemplate.opsForStream().acknowledge(queueName,"g1",id);
                } catch (Exception e) {
                    log.error("处理订单异常：{}",e);
                    /// 如果出异常，所以这个时候我们没有ack确认消息，这个消息就会一直处于pending状态
                    ///我们要手动处理这个消息
                    log.error("处理订单异常：{}",e);
                    handPendingList();
                }
                //创建订单
            }
        }

        private void handPendingList() {
            while(true){
                List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                        Consumer.from("g1", "c1"),
                        StreamReadOptions.empty().count(1),
                        StreamOffset.create(queueName, ReadOffset.from("0"))
                );
                if (list==null||list.size()==0) {
                    // no pending messages, avoid tight loop
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    continue;
                }
                MapRecord<String, Object, Object> record = list.get(0);
                RecordId id = record.getId();
                Map<Object, Object> value = record.getValue();
                VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                stringRedisTemplate.opsForStream().acknowledge(queueName,"g1",id);
                handleVoucherOrder(voucherOrder);
            }
        }

        /// 我们使用springboot提供的注解来做到一初始化就提交这个线程任务

    }
    @PostConstruct
    private void init(){
        executor.submit(new VoucherOrderHandle());
    }
    private IVoucherOrderService o;
    @Override
    public Result seckillVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        long orderId = redisIdWorker.nextId("order");
        //执行lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(),
                userId.toString(),
                String.valueOf(orderId)
        );
        //判断接过是否为0
        if (result.intValue() != 0) {
            return Result.fail(result.intValue() == 1 ? "库存不足" : "不能重复下单");
        }

        /// TODO 添加阻塞队列
        //为0，把下单信息保存到阻塞队列中
        o= (IVoucherOrderService) AopContext.currentProxy();
        return Result.ok(orderId);
    }
//    @Override
//    public Result seckillVoucher(Long voucherId) {
//        Long userId = UserHolder.getUser().getId();
//        //执行lua脚本
//        Long result = stringRedisTemplate.execute(
//                SECKILL_SCRIPT,
//                Collections.emptyList(),
//                voucherId.toString(),
//                userId.toString()
//        );
//        //判断接过是否为0
//        if (result.intValue() != 0) {
//            return Result.fail(result.intValue() == 1 ? "库存不足" : "不能重复下单");
//        }
//            long orderId = redisIdWorker.nextId("order");
//            /// TODO 添加阻塞队列
//        //为0，把下单信息保存到阻塞队列中
//        o= (IVoucherOrderService) AopContext.currentProxy();
//        return Result.ok(orderId);
//    }


    //    @Override
//    @Transactional
//    public Result seckillVoucher(Long voucherId) {
//        //查询优惠券信息
//        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
//        //判断秒杀是否开始
//        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
//            return Result.fail("秒杀尚未开始");
//        }
//        //判断秒杀是否结束
//        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
//            return Result.fail("秒杀已经结束");
//        }
//        //判断库存是否充足
//        if (voucher.getStock() < 1) {
//            return Result.fail("库存不足");
//        }
//        //扣减库存
//
//        /// 我们用乐观锁来实现扣减库存，更新时判断库存是否大于0且未被修改过，如果更新失败说明库存不足或者被其他线程修改过了
//
//        /// 这个锁的粒度是用户级别的，也就是说同一个用户的订单会被串行化处理，不同用户的订单可以并行处理，这样就避免了全局锁带来的性能问题，同时也保证了同一个用户只能购买一次
//        /// intern()方法会返回字符串常量池中的字符串对象，如果字符串已经存在于常量池中，则返回该对象的引用；如果字符串不存在于常量池中，则将该字符串添加到常量池中，并返回新对象的引用。这样就保证了同一个字符串字面量在内存中只有一个实例，从而实现了锁的共享和互斥。
//        Long userId = UserHolder.getUser().getId();
//       // SimpleRedisLock simpleRedisLock = new SimpleRedisLock(stringRedisTemplate, "order" + userId);
//        RLock lock = redisson.getLock("order" + userId);
//
//
//        boolean tryLock = lock.tryLock();
//         if (!tryLock) {
//            return Result.fail("不允许重复下单");
//        }
//        try{     /// 如果不获取代理对象的话，事务注解就不会生效，因为事务是通过AOP实现的，而AOP是基于代理模式的，如果直接调用方法的话，就相当于绕过了代理对象，事务就不会生效了，所以我们需要获取当前对象的代理对象来调用方法，这样才能保证事务注解生效。
//            IVoucherOrderService o = (IVoucherOrderService) AopContext.currentProxy();
//            return o.createVoucherOrder(voucherId, userId);
//        }finally{
//            lock.unlock();
//        }
//
//    }
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
    @Transactional
    @Override
    public void createVoucherOrder(VoucherOrder take) {
        //实现一人一单的判断
        //先查询订单表中是否存在当前用户的订单，如果存在说明已经购买过了
        Long userId = take.getUserId();
        Integer count = query().eq("user_id", userId).eq("voucher_id", take.getVoucherId()).count();
        if (count > 0) {
            log.error("买过一次");
            return;
        }
        boolean success = seckillVoucherService.update().setSql("stock = stock - 1")
                .eq("voucher_id", take.getVoucherId())//eq("stock", voucher.getStock()),这种乐观锁失败率太高了，因为在查询和更新之间可能有其他线程修改了库存，所以我们直接在更新时判断库存是否大于0，这样就避免了这个问题
                .gt("stock", 0).update();
        if (!success) {
            log.error("库存不足");
        }
        //创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);

        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(take.getVoucherId());
        save(voucherOrder);

    }
}