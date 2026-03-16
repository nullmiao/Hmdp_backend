package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;

@Slf4j
@Component
/// 封装Redis的常用操作
public class CacheClient {

    private StringRedisTemplate stringRedisTemplate;
public CacheClient (StringRedisTemplate stringRedisTemplate){
    this.stringRedisTemplate=stringRedisTemplate;
}

  public void set(String key, Object value, Long time, TimeUnit unit){
    stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value),time,unit);
  }
public void setWithLogicalExpire(String key,Object value,Long time,TimeUnit unit) {
    RedisData redisData = new RedisData();
    redisData.setData(value);
    redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
    stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
}

public <R,ID> R queryWithPassThrough(String keyPrefix, ID id, Class<R> type, Function<ID,R> dbFallback,Long time,TimeUnit unit){
String key=keyPrefix+id;
    String json = stringRedisTemplate.opsForValue().get(key);
    if (StrUtil.isNotBlank(json)) {
        R bean = JSONUtil.toBean(json,type);
        return bean;
    }
    if (json != null) {
        return null;
    }
    R r= dbFallback.apply(id);
    if (r == null) {
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
        return null;
    }
this.set(key,r,time,unit);
    return r;
}
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

   /// ///逻辑过期解决缓存击穿

    public <R,ID> R queryWithLogicExpire(String prefix,ID id,Class<R> type,Function<ID,R> dbFallback,Long time,TimeUnit unit) {
String key=prefix+id;
        String json = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isBlank(json)) {
            return null;

        }
//反序列化
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);

        R r= JSONUtil.toBean((JSONObject) redisData.getData(), type);
        LocalDateTime expireTime = redisData.getExpireTime();
        if (expireTime.isAfter(LocalDateTime.now())) {
            return r;
        }
        String lockKey = LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(lockKey);
        if (isLock) {
            //获取锁成功，开启独立线程进行缓存重建
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    R r1= dbFallback.apply(id);
this.setWithLogicalExpire(key,r1,time,unit);

                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    //释放锁
                    unlock(lockKey);
                }
            });
        }

        return r;
    }

    private boolean tryLock(String key){
        Boolean b = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(b);
    }
    //释放锁
    private void unlock(String key){
        stringRedisTemplate.delete(key);
    }


}
