package com.hmdp.utils;

import cn.hutool.aop.interceptor.SpringCglibInterceptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component
public class RedisIdWorker {
    /**
     * 序列号占用的位数
     */
    private static final int COUNT_BITS =32 ;
    private StringRedisTemplate stringRedisTemplate;
    public RedisIdWorker(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }
    private static final long BEGIN_TIMESTAMP = 1605448861L;
    public long nextId(String keyPrefix) {
   //生成时间戳
        LocalDateTime now = LocalDateTime.now();
         long nowSecond = now.toEpochSecond(ZoneOffset.UTC);
        long timestamp = nowSecond - BEGIN_TIMESTAMP;
        //生成序列号
        String date = now.format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        //自增
        long count = stringRedisTemplate.opsForValue().increment("icr:" + keyPrefix + ":" + date);
        //拼接并返回
        return timestamp << COUNT_BITS | count;
    }

    public static void main(String[] args) {
        LocalDateTime localDateTime = LocalDateTime.of(2020, 11, 11, 11, 11, 11);
        long second = localDateTime.toEpochSecond(ZoneOffset.UTC);
        System.out.println(second);
        System.out.println(LocalDateTime.now());
    }
}
