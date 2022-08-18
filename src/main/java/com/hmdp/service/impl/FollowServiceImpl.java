package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollectionUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
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
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private IUserService userService;

    @Override
    public Result follow(Long followUserId, Boolean isFollow) {

        // 1.获取登录用户
        Long userId = UserHolder.getUser().getId();
        String key = "follows:" + userId;
        if (isFollow) {
            // 2.关注
            Follow follow = new Follow();
            follow.setUserId(userId);
            follow.setFollowUserId(followUserId);
            boolean isSuccess = save(follow);
            if (isSuccess) {
                // 存入关注集合Set
                stringRedisTemplate.opsForSet().add(key, followUserId.toString());
            }
        } else {
            // 3.取关，删除 delete from tb_follow where user_id = ? and follow_user_id = ?
            boolean isSuccess = remove(new QueryWrapper<Follow>()
                            .eq("user_id", userId).eq("follow_user_id", followUserId));
            // 从Redis中的Set集合移除
            if (isSuccess) {
                stringRedisTemplate.opsForSet().remove(key, followUserId.toString());
            }
        }
        return Result.ok();
    }

    @Override
    public Result isFollow(Long followUserId) {

        // 1.获取登录用户
        Long userId = UserHolder.getUser().getId();

        // 2.查询当前是否关注作者
        Integer count = query().eq("user_id", userId).eq("follow_user_id", followUserId).count();

        return Result.ok(count > 0);
    }

    @Override
    public Result followCommons(Long targetUserId) {
        // 1.当前用户
        Long userId = UserHolder.getUser().getId();

        // 2.两个key
        String key = "follows:" + userId;
        String targetKey =  "follows:" + targetUserId;

        // 3.取交集
        Set<String> intersect = stringRedisTemplate.opsForSet().intersect(key, targetKey);
        if (CollectionUtil.isEmpty(intersect)) {
            // 无交集
            return Result.ok(Collections.emptyList());
        }

        // 4.将交集中的userId解析成Long
        List<Long> ids = intersect.stream().map(Long::valueOf).collect(Collectors.toList());

        // 5.查询出用户
        // List<User> users = userService.listByIds(ids);
        List<UserDTO> userDTOS = userService.query().in("id", ids).list()
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        return Result.ok(userDTOS);
    }
}
