package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.collection.CollectionUtil;
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
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpSession;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
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

    @Override
    public Result sign() {

        // 1.获取当前登录用户
        Long userId = UserHolder.getUser().getId();

        // 2.获取日期
        LocalDateTime now = LocalDateTime.now();

        // 3.拼接key：每个用户每月的签到情况有一个key
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = RedisConstants.USER_SIGN_KEY + userId + keySuffix;

        // 4.获取今天是本月的第几天
        int dayOfMonth = now.getDayOfMonth();

        // 5.写入Redis SETBIT key offset 1
        stringRedisTemplate.opsForValue().setBit(key, dayOfMonth - 1, true);
        return Result.ok();
    }

    @Override
    public Result signCount() {

        // 1.获取当前用户id
        Long userId = UserHolder.getUser().getId();

        // 2. 当前时间，当前月数
        LocalDateTime now = LocalDateTime.now();
        int dayOfMonth = now.getDayOfMonth();

        // 2.拼接key
        String key = RedisConstants.USER_SIGN_KEY + userId +
                now.format(DateTimeFormatter.ofPattern(":yyyyMM"));

        // 3.获取当前用户本月的签到数据：BITFIELD key GET u[dayOfMonth] 0：假设今天是15号，无符号获取15个元素，从0号开始
        List<Long> result = stringRedisTemplate.opsForValue().bitField(key,
                BitFieldSubCommands.create().get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth)).valueAt(0));

        // 4.结果判断
        if (CollectionUtil.isEmpty(result)) {
            // 没有签到结果
            return Result.ok();
        }
        // 本月签到结果
        Long num = result.get(0);
        if (null == num || num == 0) {
            return Result.ok(0);
        }

        // 计算连续签到天数
        int count = 0;
        while (true) {
            // 获取num的最后一位
            long lastBit = (num & 1);
            if (1L == lastBit) {
                count++;
                // 无符号右移1位：方便下一次循环能获得倒数第二位
                num = num >>> 1;
            } else {
                break;
            }
        }
        return Result.ok(count);
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
