package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {
@Resource
private StringRedisTemplate stringRedisTemplate;
    @Resource
    private IShopTypeService typeService;
    @Override
    public Result queryList() {
        // 1. 从Redis获取缓存
        String cacheList = stringRedisTemplate.opsForValue().get("cache:type:list");

        // 2. 缓存存在：解析JSON数组字符串为List<ShopType>
        if (StrUtil.isNotBlank(cacheList)) {
            // 关键修复：用toList解析数组，而非toBean（toBean默认解析对象）
            List<ShopType> typeList = JSONUtil.toList(cacheList, ShopType.class);
            return Result.ok(typeList);
        }

        // 3. 缓存不存在：查询数据库
        List<ShopType> typeList = typeService.query().orderByAsc("sort").list();
        if (typeList == null || typeList.isEmpty()) {
            return Result.fail("商铺类型不存在");
        }

        // 4. 写入Redis（存JSON数组字符串）
        stringRedisTemplate.opsForValue().set("cache:type:list", JSONUtil.toJsonStr(typeList));

        // 5. 返回数据库查询结果
        return Result.ok(typeList);
    }
}
