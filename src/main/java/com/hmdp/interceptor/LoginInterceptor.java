package com.hmdp.interceptor;

import com.hmdp.constant.RespConstant;
import com.hmdp.constant.SessionConstant;
import com.hmdp.dto.UserDTO;
import com.hmdp.utils.UserHolder;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

/**
 * 描述：自定义拦截器，但是只拦截需要登录才能访问的接口
 *
 * @author txl
 * @date 2022-07-23 23:44
 */
public class LoginInterceptor implements HandlerInterceptor {


    // 前置拦截:controller之前，基于session的实现
    // @Override
    @Deprecated
    public boolean preHandleBySession(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 1.获取session
        HttpSession session = request.getSession();

        // 2.获取session中的用户
        Object user = session.getAttribute(SessionConstant.ATTRIBUTE_USER);

        // 3.判断session中是否存有用户
        if (null == user) {
            response.setStatus(Integer.parseInt(RespConstant.CODE_UNAUTHORIZED));
            return false;
        }

        // 4.存在：保存用户信息到ThreadLocal，保存到当前线程绑定的ThreadLocal
        UserHolder.saveUser((UserDTO) user);

        // 5.放行
        return true;
    }

    // 前置拦截:controller之前，基于redis的实现
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {

        // 1.如果ThreadLocal没有保存用户信息，拦截
        if (UserHolder.getUser() == null) {
            response.setStatus(Integer.parseInt(RespConstant.CODE_UNAUTHORIZED));
            return false;
        }

        // 6.放行
        return true;
    }
}
