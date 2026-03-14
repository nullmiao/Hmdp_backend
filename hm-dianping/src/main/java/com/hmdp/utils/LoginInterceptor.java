package com.hmdp.utils;


import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import javax.servlet.http.HttpSession;
public class LoginInterceptor implements HandlerInterceptor {

     /**
      * 目标方法执行之前
      * @return true：放行， false：拦截
      */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
     //1.获取session
        //2.获取用户信息
        //3.判断用户信息是否存在
        //4.存在，放行
        //5.不存在，返回401状态码，拦截
      HttpSession session=request.getSession();
        Object user= session.getAttribute("user");
        if (user==null){
            //没有登录，拦截
            response.setStatus(401);
            return false;

        }
        UserHolder.saveUser((UserDTO) user);
        return true;
    }
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        // 1. 执行完毕，清除用户信息
        UserHolder.removeUser();
    }
}
