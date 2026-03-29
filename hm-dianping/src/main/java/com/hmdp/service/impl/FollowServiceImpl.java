package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {
@Autowired
    StringRedisTemplate stringRedisTemplate;

@Autowired
private IUserService userService;
    @Override
    public Result follow(Long followUserId, Boolean isFollow) {
        //判断到底是关注了还是取关了
        long userId = UserHolder.getUser().getId();
        String key="follows"+userId;
        if (isFollow) {
            Follow follow = new Follow();
            follow.setFollowUserId(followUserId);
            follow.setUserId(userId);

            boolean isSuccess = save(follow);
            if (isSuccess) {

                stringRedisTemplate.opsForSet().add(key, followUserId.toString());
            }

        }
        else{
            boolean isSuccess = remove(new QueryWrapper<Follow>().eq("user_id", userId).eq("follow_user_id", followUserId));
            if (isSuccess) {
                stringRedisTemplate.opsForSet().remove(key, followUserId.toString());
            }

        }
        return Result.ok();
    }

    @Override
    public Result isFollow(Long followUserId) {
        Long userId = UserHolder.getUser().getId();
        //查询是否关注
        Integer count = query().eq("user_id", userId).eq("follow_user_id", followUserId).count();

        return Result.ok(count>0);
    }

    @Override
    public Result followCommons(Long followUserId) {
        //获取当前登录用户然后求交集
        Long userId = UserHolder.getUser().getId();
        String key1="follows"+userId;
        String key2="follows"+followUserId;
        Set<String> intersect = stringRedisTemplate.opsForSet().intersect(key1, key2);
        if (intersect.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        List<Long> collect = intersect.stream().map(Long::parseLong).collect(Collectors.toList());
        List<UserDTO> users = userService.listByIds(collect).stream().
                map(user-> BeanUtil.copyProperties(user,UserDTO.class)).collect(Collectors.toList());
        return Result.ok(users);
    }
}
