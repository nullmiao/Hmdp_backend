package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import com.hmdp.utils.SystemConstants;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.geo.Metrics;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {
@Resource
    private StringRedisTemplate stringRedisTemplate;
@Autowired
private CacheClient cacheClient;
    @Override
    public Result queryById(Long id) throws InterruptedException {
     //Shop shop = queryWithPassThrough(id);
      //Shop shop = queryWithMutex(id);
    //Shop shop = queryWithLogicExpire(id);

/// 穿透
         //Shop shop = cacheClient.queryWithPassThrough(CACHE_SHOP_KEY, id, Shop.class, this::getById,CACHE_SHOP_TTL,TimeUnit.MINUTES);

      ///击穿
        Shop shop = cacheClient.queryWithLogicExpire(CACHE_SHOP_KEY, id, Shop.class, this::getById,CACHE_SHOP_TTL,TimeUnit.MINUTES);

        if (shop == null) {
            return Result.fail("商铺不存在");
        }
        return Result.ok(shop);

    }
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    public Shop queryWithLogicExpire(Long id){

        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
        if (StrUtil.isBlank(shopJson)) {
           return null;

        }
//反序列化
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
       JSONObject data = (JSONObject) redisData.getData();
        Shop shop= JSONUtil.toBean(data, Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        if (expireTime.isAfter(LocalDateTime.now())) {
            return shop;
        }
        String lockKey = LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(lockKey);
        if (isLock) {
            //获取锁成功，开启独立线程进行缓存重建
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    this.saveShop2Redis(id, 20L);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                } finally {
                    //释放锁
                    unlock(lockKey);
                }
            });
        }

        return shop;
    }
    /// ///使用互斥锁解决缓存击穿问题
    public Shop queryWithMutex(Long id) throws InterruptedException {
        //1.redis查询商铺缓存
        //2.如果存在，直接返回
        //3.如果不存在，根据id查询数据库
        //4.不存在，返回错误信息
        //5.存在，写入redis并返回
        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
        if (StrUtil.isNotBlank(shopJson)) {
            Shop bean = JSONUtil.toBean(shopJson, Shop.class);
            return bean;
        }
        /// 如果命中的是空字符串，说明之前查询过这个商铺但不存在，直接返回错误信息，避免再次访问数据库。
        if (shopJson != null) {
            return null;
        }
        ///实现缓存重建
        String lockKey = LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(lockKey);
        if (!isLock) {
            //获取锁失败，休眠并重试
            Thread.sleep(50);
            queryWithMutex(id);
        }
            Shop shop = getById(id);

            if (shop == null) {
                /// 这段代码是为了防止缓存穿透而添加的。当查询一个不存在的商铺时，数据库会返回null。为了避免每次查询都访问数据库，
                ///我们将一个空字符串写入Redis，并设置一个较短的过期时间（CACHE_NULL_TTL）。
                ///这样，当下次查询同一个不存在的商铺时，Redis会直接返回这个空字符串，而不会访问数据库，从而有效地防止了缓存穿透问题。
                stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);

            unlock(lockKey);

            return shop;
        }

/// TODO使用互斥锁解决缓存击穿问题
// 定义一个互斥锁的键，通常是原缓存键加上一个后缀，例如 ":lock"。
// 在查询数据库之前，尝试获取这个互斥锁。如果获取成功，说明当前线程可以安全地访问数据库并更新缓存；如果获取失败，说明另一个线程正在访问数据库，当前线程应该等待一段时间后重试。

public Shop queryWithPassThrough(Long id){
    //1.redis查询商铺缓存
    //2.如果存在，直接返回
    //3.如果不存在，根据id查询数据库
    //4.不存在，返回错误信息
    //5.存在，写入redis并返回
    String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
    if (StrUtil.isNotBlank(shopJson)) {
        Shop bean = JSONUtil.toBean(shopJson, Shop.class);
        return bean;
    }
    /// 如果命中的是空字符串，说明之前查询过这个商铺但不存在，直接返回错误信息，避免再次访问数据库。
    if (shopJson != null) {
        return null;
    }
    Shop shop = getById(id);
    if (shop == null) {
        /// 这段代码是为了防止缓存穿透而添加的。当查询一个不存在的商铺时，数据库会返回null。为了避免每次查询都访问数据库，
        ///我们将一个空字符串写入Redis，并设置一个较短的过期时间（CACHE_NULL_TTL）。
        ///这样，当下次查询同一个不存在的商铺时，Redis会直接返回这个空字符串，而不会访问数据库，从而有效地防止了缓存穿透问题。
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, "",CACHE_NULL_TTL, TimeUnit.MINUTES);
        return null;
    }
    stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL, TimeUnit.MINUTES);
    return shop;
}
//获取锁
private boolean tryLock(String key){
        Boolean b = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(b);
    }
    //释放锁
    private void unlock(String key){
        stringRedisTemplate.delete(key);
    }
public void saveShop2Redis(Long id,Long expireTime) throws InterruptedException {
    //1.查询店铺数据
    Shop shop = getById(id);
    Thread.sleep(200);
   //2.封装逻辑过期时间
    RedisData redisData = new RedisData();
    redisData.setData(shop);
    redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireTime));
    //3.写入redis
stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));
}
    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id=shop.getId();
        if (id==null) {
            return Result.fail("商铺id不能为空");
        }
        updateById(shop);
        stringRedisTemplate.delete(CACHE_SHOP_KEY + shop.getId());
        return Result.ok();
    }

    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        // 1. 判断是否需要根据坐标查询
        if (x == null || y == null) {
            // 不需要坐标查询，按数据库查询
            Page<Shop> page = query()
                    .eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            // 返回数据
            return Result.ok(page.getRecords());
        }

        // 2. 计算分页参数
        int from = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        int end = current * SystemConstants.DEFAULT_PAGE_SIZE;

        // 3. 查询Redis、按照距离排序、分页。结果：shopId、distance
        String key = SHOP_GEO_KEY + typeId;
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo()
                .search(
                        key,
                        GeoReference.fromCoordinate(x, y),
                        new Distance(5000, Metrics.KILOMETERS),
                        RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs()
                                .includeDistance()
                                .limit(end)
                );
        if(results==null){
            return Result.ok(Collections.emptyList());
        }
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = results.getContent();
        if(list.size()<from){
            return Result.ok(Collections.emptyList());
        }
        List<Long> ids = new ArrayList<>(list.size());
        Map<String,Distance> distanceMap = new HashMap<>(list.size());
        list.stream().skip(from).forEach(result->
                {
                    String shopIdStr = result.getContent().getName();
                    ids.add(Long.parseLong(shopIdStr));
                    Distance distance = result.getDistance();
                    distanceMap.put(shopIdStr,distance);
                }
                );
        String idStr = StrUtil.join(",",ids);
        List<Shop> shops = query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list();

        for (Shop shop : shops) {
            shop.setDistance(distanceMap.get(shop.getId().toString()).getValue());
        }
        return Result.ok(shops);
    }
}
