package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.constant.RedisConstants;
import com.hmdp.constant.RespConstant;
import com.hmdp.constant.SessionConstant;
import com.hmdp.constant.SystemConstants;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;


/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 登录环节1：发送验证码，基于session实现
     * @param phone
     * @param session
     * @return
     */
    @Override
    public Result sendCode(String phone, HttpSession session) {
        // 1.校验手机号
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail(RespConstant.MESSAGE_ILLEGAL_PHONE_NUMBER);
        }
        // 2.生成验证码
        String code = RandomUtil.randomNumbers(6);

        // 3.保存验证码到session
        session.setAttribute(SessionConstant.ATTRIBUTE_CODE, code);

        // 4.模拟发送验证码
        log.debug("发送验证码成功，验证码：{}", code);

        return Result.ok();
    }

    /**
     * 登录环节1：发送验证码，基于Redis实现
     * @param phone
     * @param session
     * @return
     */
    @Override
    public Result sendCodeByRedis(String phone, HttpSession session) {
        // 1.校验手机号
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail(RespConstant.MESSAGE_ILLEGAL_PHONE_NUMBER);
        }
        // 2.生成验证码
        String code = RandomUtil.randomNumbers(6);

        // 3.保存验证码到redis：过期时间2分钟
        stringRedisTemplate.opsForValue().set(RedisConstants.LOGIN_CODE_KEY + phone,
                code,
                RedisConstants.CACHE_NULL_TTL,
                TimeUnit.MINUTES);

        // 4.模拟发送验证码
        log.debug("发送验证码成功，验证码：{}", code);

        return Result.ok();
    }

    /**
     * 登录环节2：登录校验，基于session
     * @param loginForm
     * @param session
     * @return
     */
    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        // 1.校验手机号
        String phone = loginForm.getPhone();
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail(RespConstant.MESSAGE_ILLEGAL_PHONE_NUMBER);
        }

        // 2.校验验证码
        String cacheCode = (String)session.getAttribute(SessionConstant.ATTRIBUTE_CODE);
        String code = loginForm.getCode();
        if (cacheCode == null || !cacheCode.equals(code)) {
            return Result.fail(RespConstant.MESSAGE_ERROR_CODE);
        }

        // 3.判断用户是否存在
        User user = query().eq("phone", phone).one();
        if (null == user) {
            // 3.1.创建新用户
            user = createUserWithPhone(phone);
        }

        // 4.只需要往session存入基本用户信息即可，节省内存空间。服务器session的有效期是30min
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);

        // 5.保存用户信息到session
        session.setAttribute(SessionConstant.ATTRIBUTE_USER, userDTO);

        // 6.基于session登录，不需要返回登录凭证
        return Result.ok();
    }

    /**
     * 登录环节2：登录校验，基于redis
     * @param loginForm
     * @param session
     * @return
     */
    @Override
    public Result loginByRedis(LoginFormDTO loginForm, HttpSession session) {
        // 1.校验手机号
        String phone = loginForm.getPhone();
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail(RespConstant.MESSAGE_ILLEGAL_PHONE_NUMBER);
        }

        // 2.校验验证码
        String cacheCode = stringRedisTemplate.opsForValue().get(RedisConstants.LOGIN_CODE_KEY + phone);
        if (cacheCode == null || !cacheCode.equals(loginForm.getCode())) {
            return Result.fail(RespConstant.MESSAGE_ERROR_CODE);
        }

        // 3.判断用户是否存在
        User user = query().eq("phone", phone).one();
        if (null == user) {
            // 3.1.创建新用户
            user = createUserWithPhone(phone);
        }

        // 4.往redis存入用户信息：会在拦截器那里刷新过期时间
        String token = UUID.randomUUID().toString(true); // 不带中划线的UUID
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        // 4.1.注意userDTO中的id是Long类型，直接beanToMap得到的Map中的id字段也是Long，没法存储到Redis
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true)
                        .setFieldValueEditor((fieldName, fieldVale) -> fieldVale.toString()));
        stringRedisTemplate.opsForHash().putAll(RedisConstants.LOGIN_USER_KEY + token, userMap);
        stringRedisTemplate.expire(RedisConstants.LOGIN_USER_KEY + token,
                RedisConstants.LOGIN_USER_TTL, TimeUnit.MINUTES);

        // 5.基于session登录，不需要返回登录凭证
        return Result.ok(token);
    }

    private User createUserWithPhone(String phone) {
        // 1.创建用户
        User user = new User();
        user.setPhone(phone);
        user.setNickName(SystemConstants.USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));

        // 2.保存用户
        save(user);
        return user;
    }
}
