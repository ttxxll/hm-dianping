package com.hmdp.interceptor;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.constant.RedisConstants;
import com.hmdp.constant.ReqConstant;
import com.hmdp.dto.UserDTO;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;

/**
 * 描述：
 *  因为LoginInterceptor只会拦截需要登录才能访问的接口，如果在这个里面做刷新token的话，会出现问题。
 *  当用户登录后仅访问LoginInterceptor没有拦截的部分，那么就不会刷新token。一段时间后就会导致token
 *  失效。
 *  所以我们还需要再加一层拦截器，拦截所有接口，主要做刷新token的功能。
 *
 * @author txl
 * @date 2022-07-23 23:44
 */
public class RefreshTokenInterceptor implements HandlerInterceptor {

    private StringRedisTemplate stringRedisTemplate;

    /**
     * RefreshTokenInterceptor对象在使用时是我们自己创建的，不是Spring创建的所以Spring也不能帮我们注入其他属性，所以这里不能用Autowired注解
     * 我们用构造函数注入：哪里使用了这个对象，哪里调用这个构造函数。MvcConfig中使用了
     * @param stringRedisTemplate
     */
    public RefreshTokenInterceptor(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }


    // 前置拦截:controller之前，基于redis的实现
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 1.拿到token
        String token = request.getHeader(ReqConstant.HEADER_AUTHORIZATION);
        if (StrUtil.isBlank(token)) {
            // 这个拦截器不做登录校验
            return true;
        }

        // 2.获取redis中的用户
        String key = RedisConstants.LOGIN_USER_KEY + token;
        Map<Object, Object> userMap = stringRedisTemplate.opsForHash().entries(key);


        // 3.判断redis中是否存有用户
        if (userMap.isEmpty()) {
            // 这个拦截器不做登录校验
            return true;
        }

        // 4.刷新token：注意这里的user对象，每个线程/请求创建的都是新对象。
        UserDTO user = BeanUtil.fillBeanWithMap(userMap, new UserDTO(), false); // false：不忽略异常
        UserHolder.saveUser(user); // 保存用户信息到当前线程
        // stringRedisTemplate.expire(key, RedisConstants.LOGIN_USER_TTL, TimeUnit.MINUTES);

        // 5.放行
        return true;
    }

    // controller完成之后
    @Override
    public void postHandle(HttpServletRequest request, HttpServletResponse response, Object handler, ModelAndView modelAndView) throws Exception {

    }

    // 渲染之后，返回给用户之前
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        // 移除用户：避免内存泄漏
        UserHolder.removeUser();
    }
}
