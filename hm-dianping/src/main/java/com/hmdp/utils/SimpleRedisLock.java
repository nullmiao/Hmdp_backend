package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements Ilock{
    private static final String KEY_PREFIX="lock:";
    private static final String ID_PREFIX= UUID.randomUUID().toString(true)+"-";
    private String name;
    private StringRedisTemplate stringRedisTemplate;
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;
    static{
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }
    public SimpleRedisLock(StringRedisTemplate stringRedisTemplate, String name) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.name = name;
    }



    @Override
    public boolean tryLock(long timeoutSec) {
        //获取当前线程的id
        String threadId = ID_PREFIX+Thread.currentThread().getId();


        Boolean b = stringRedisTemplate.opsForValue().setIfAbsent(KEY_PREFIX+name,threadId, timeoutSec, TimeUnit.SECONDS);
        /// ///防止拆箱过程中出现问题
        return Boolean.TRUE.equals(b);

    }
//由于上述方法不是原子性的
    //我们基于lua脚本来改造这个方法
    @Override
    public void unlock() {
        /// redis中的stringRedisTemplate中的api的execute函数可以调用lua脚本
        stringRedisTemplate.execute(
                UNLOCK_SCRIPT,
                Collections.singletonList(KEY_PREFIX+name),
                ID_PREFIX+Thread.currentThread().getId()
        );
    }

//    @Override
//    public void unlock() {
//        String threadId=ID_PREFIX+Thread.currentThread().getId();
//        String id = stringRedisTemplate.opsForValue().get(KEY_PREFIX + name);
//        //判断标识是否一样
//        if(threadId.equals(id)){
//            stringRedisTemplate.delete(KEY_PREFIX + name);
//        }
//    }

}
