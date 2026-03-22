package com.hmdp;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.boot.test.context.SpringBootTest;

import javax.annotation.Resource;
import java.util.concurrent.TimeUnit;

/**
 * 测试 Redisson 分布式锁的可重入性
 * 核心：同一线程可以重复获取同一把锁，不会死锁，且解锁次数需与加锁次数匹配
 */
@Slf4j
@SpringBootTest  // 关键修正：测试类用 @SpringBootTest 启动 Spring 容器
public class RedissonTest {

    @Resource
    private RedissonClient redisson;
    private RLock lock;

    @BeforeEach
    public void init() {
        // 获取名为 "order" 的分布式可重入锁
        lock = redisson.getLock("order");
    }

    @Test
    public void testReentrantLock() throws InterruptedException {
        // 第一次获取锁（非阻塞式，超时时间3秒）
        boolean tryLock = lock.tryLock(3, TimeUnit.SECONDS);
        if (!tryLock) {
            log.error("【方法一】获取锁失败");
            return;
        }
        try {
            log.info("【方法一】获取锁成功，开始执行任务");
            // 调用test2，测试同一线程重入锁
            test2();
        } finally {
            // 只解锁一次（与第一次加锁对应）
            if (lock.isHeldByCurrentThread()) { // 安全解锁：先判断当前线程是否持有锁
                lock.unlock();
                log.warn("【方法一】释放锁成功");
            }
        }
    }

    private void test2() {
        // 同一线程再次获取同一把锁（重入）
        boolean tryLock = lock.tryLock();
        if (!tryLock) {
            log.error("【方法二】获取锁失败（如果打印这句，说明锁不可重入）");
            return;
        }
        try {
            log.info("【方法二】重入锁成功，执行子任务");
            // 可以继续重入（比如调用test3，验证多层重入）
        } finally {
            // 解锁一次（与test2的加锁对应）
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
                log.warn("【方法二】释放重入锁成功");
            }
        }
    }
}