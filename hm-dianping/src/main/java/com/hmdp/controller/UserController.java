package com.hmdp.controller;


import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.entity.UserInfo;
import com.hmdp.service.IUserInfoService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;

/**
 * <p>
 * 前端控制器
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@RestController
@RequestMapping("/user")
@Api(tags = "用户接口")
public class UserController {
/// Resource默认按照名称注入
/// Autowired默认按照类型注入
    @Resource
    private IUserService userService;

    @Resource
    private IUserInfoService userInfoService;

    /**
     * 发送手机验证码
     */
    @ApiOperation("发送手机验证码")
    @PostMapping("code")
    public Result sendCode(@RequestParam("phone") String phone, HttpSession session) {
        return userService.sendCode(phone, session);
    }

    /**
     * 登录功能
     * @param loginForm 登录参数，包含手机号、验证码；或者手机号、密码
     */
    @PostMapping("/login")
    @ApiOperation("用户登录")
    public Result login(@RequestBody LoginFormDTO loginForm, HttpSession session){

        return userService.login(loginForm, session);
    }

    /**
     * 登出功能
     * @return 无
     */
    @PostMapping("/logout")
    @ApiOperation("用户登出")
    public Result logout(){
        // TODO 实现登出功能
        return Result.fail("功能未完成");
    }

    @GetMapping("/me")
    @ApiOperation("获取当前用户信息")
    public Result me(){

        UserDTO user = UserHolder.getUser();

        return Result.ok(user);
    }

    @GetMapping("/info/{id}")
    @ApiOperation("根据用户ID获取用户详情")
    public Result info(@PathVariable("id") Long userId){
        // 查询详情
        UserInfo info = userInfoService.getById(userId);
        if (info == null) {
            // 没有详情，应该是第一次查看详情
            return Result.ok();
        }
        info.setCreateTime(null);
        info.setUpdateTime(null);
        // 返回
        return Result.ok(info);
    }
    @GetMapping("/{id}")
    public Result queryUserById(@PathVariable("id") Long userId){
        // 查询详情
        User user = userService.getById(userId);
        if (user == null) {
            return Result.ok();
        }
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        // 返回
        return Result.ok(userDTO);
    }
}
