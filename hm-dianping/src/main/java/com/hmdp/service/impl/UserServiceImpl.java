package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result sendCode(String phone, HttpSession session) {
        //1.校验手机号,不符合要求返回错误信息
       //这里的字符串是一个正则表达式
       if (RegexUtils.isPhoneInvalid(phone)){
           return Result.fail("手机号格式错误");
       }

        //2.生成验证码
        String code = RandomUtil.randomNumbers(6);

       stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY +phone,code,2, TimeUnit.MINUTES);
        //3.保存验证码到session
session.setAttribute("code",code);
        //4.发送验证码

        /// 这里是模拟的发送验证码服务，实际项目中应该调用第三方短信服务商的API来发送短信验证码
        /// 比如阿里云短信服务、腾讯云短信服务等
            log.debug("发送短信验证码成功，验证码：{}",code);
        return Result.ok();
    }

    /**
     * 登录功能
     * @param loginForm 登录表单，包含手机号与验证码
     * @param session HTTP 会话，用于临时存放验证码（模拟场景）
     * @return 登录结果，成功时包含 status ok
     */


//    public Result login(LoginFormDTO loginForm, HttpSession session) {
//        // 1.校验手机号
//        String phone = loginForm.getPhone();
//        if (RegexUtils.isPhoneInvalid(phone)) {
//            // 2.如果不符合，返回错误信息
//            return Result.fail("手机号格式错误！");
//        }
////        // 3.校验验证码
////        Object cacheCode = session.getAttribute("code");
//        // 3.从redis获取验证码并校验
//        String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
//        String code = loginForm.getCode();
//
//        // @TODO 先不校验验证码
///*        if(cacheCode == null || !cacheCode.equals(code)){
//            //3.不一致，报错
//            return Result.fail("验证码错误");
//        }*/
//        //注释掉以上部分
//
//
//        //一致，根据手机号查询用户
//        User user = query().eq("phone", phone).one();
//
//        //5.判断用户是否存在
//        if(user == null){
//            //不存在，则创建
//            user =  createUserWithPhone(phone);
//        }
//
//        // 7.保存用户信息到 redis中
//        // 7.1.随机生成token，作为登录令牌
//        String token = UUID.randomUUID().toString(true);
//        // 7.2.将User对象转为HashMap存储
//        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
//        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(), //beanToMap方法执行了对象到Map的转换
//                CopyOptions.create()
//                        .setIgnoreNullValue(true) //BeanUtil在转换过程中忽略所有null值的属性
//                        .setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString())); //对于每个字段值，它简单地调用toString()方法，将字段值转换为字符串。
//        // 7.3.存储
//        String tokenKey = LOGIN_USER_KEY + token;
//        stringRedisTemplate.opsForHash().putAll(tokenKey, userMap);
//        // 7.4.设置token有效期
//        stringRedisTemplate.expire(tokenKey, LOGIN_USER_TTL, TimeUnit.MINUTES);
//
//        // 8.返回token
//        return Result.ok(token);
//
//    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        //用户提交
        //1.校验手机号,
        String phone = loginForm.getPhone();
        if (RegexUtils.isPhoneInvalid(phone)){
            return Result.fail("手机号格式错误");
        }
        String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
        //2.校验验证码

        String code=loginForm.getCode();
         if (cacheCode==null||!cacheCode.equals(code)){
             //3.不一致返回错误信息
             return Result.fail("验证码错误");
         }
        //4.一致根据手机号查询用户

        User user = query().eq("phone", phone).one();
        //5.判断是否存在
        if(user==null){
            //6.不存在创建新用户
           user=createUserWithPhone(phone);
        }
        //6.保存用户信息
        String token = UUID.randomUUID().toString(true);
        UserDTO userDTO= BeanUtil.copyProperties(user, UserDTO.class);
        //使用BeanUtil把对象转变成一个map
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO);

        // StringRedisTemplate 期望 hash 的 key/value 都是 String 类型，
        // 因此在写入 Redis 前把所有 value 强制转为字符串，避免 Long 等类型导致的序列化/类型转换异常
        java.util.Map<String, String> redisMap = new java.util.HashMap<>();
        for (java.util.Map.Entry<String, Object> entry : userMap.entrySet()) {
            Object val = entry.getValue();
            if (val != null) {
                redisMap.put(entry.getKey(), String.valueOf(val));
            }
        }

        String tokenKey = LOGIN_USER_KEY + token;
        stringRedisTemplate.opsForHash().putAll(tokenKey, redisMap);
        stringRedisTemplate.expire(tokenKey, LOGIN_USER_TTL, TimeUnit.MINUTES);
        return Result.ok(token);
    }

    @Override
    public Result sign() {
        // 1. 获取当前登录用户
        Long userId = UserHolder.getUser().getId();
        // 2. 获取日期
        LocalDateTime now = LocalDateTime.now();
        // 3. 拼接key
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + userId + keySuffix;
        // 4. 获取今天是本月的第几天
        int dayOfMonth = now.getDayOfMonth();
        // 5. 写入Redis SETBIT key offset 1
        stringRedisTemplate.opsForValue().setBit(key, dayOfMonth - 1, true);
        return Result.ok();
    }

    private User createUserWithPhone(String phone) {
        User user = new User();
        user.setPhone(phone);
        //随机生成一个昵称
        user.setNickName(USER_NICK_NAME_PREFIX+RandomUtil.randomString(10));
        save(user);
        return user;
    }
}
