package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

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
    @Override
    public Result queryById(Long id) {
        //1.redis查询商铺缓存
        //2.如果存在，直接返回
        //3.如果不存在，根据id查询数据库
        //4.不存在，返回错误信息
        //5.存在，写入redis并返回
        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
        if (StrUtil.isNotBlank(shopJson)) {
            Shop bean = JSONUtil.toBean(shopJson, Shop.class);
            return Result.ok(bean);
        }
        /// 如果命中的是空字符串，说明之前查询过这个商铺但不存在，直接返回错误信息，避免再次访问数据库。
        if (shopJson != null) {
            return Result.fail("商铺不存在");
        }
        Shop shop = getById(id);
        if (shop == null) {
            /// 这段代码是为了防止缓存穿透而添加的。当查询一个不存在的商铺时，数据库会返回null。为了避免每次查询都访问数据库，
            ///我们将一个空字符串写入Redis，并设置一个较短的过期时间（CACHE_NULL_TTL）。
            ///这样，当下次查询同一个不存在的商铺时，Redis会直接返回这个空字符串，而不会访问数据库，从而有效地防止了缓存穿透问题。
            stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, "",CACHE_NULL_TTL, TimeUnit.MINUTES);
            return Result.fail("商铺不存在");
        }
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL, TimeUnit.MINUTES);
        return Result.ok(shop);

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
}
