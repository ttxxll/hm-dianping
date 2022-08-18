package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.constant.RedisConstants;
import com.hmdp.constant.SystemConstants;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
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
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Resource
    private IUserService userService;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryBlogById(Long id) {

        // 1.查询blog
        Blog blog = getById(id);
        if (null == blog) {
            return Result.fail("笔记不存在！");
        }

        // 2.查询写这个blog的用户，及是否被当前用户点赞过
        queryBlogUser(blog);
        return Result.ok(blog);
    }

    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = this.query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));

        // 获取当前页数据
        List<Blog> records = page.getRecords();

        // 查询用户：forEach遍历的过程中，调用queryBlogUser方法，且参数是每次遍历的元素
        records.forEach(this::queryBlogUser);
        return Result.ok(records);
    }

    /**
     * 用Sorted Set来记录给该Blog点赞的用户，排序依据是点赞时间戳
     * 点赞越早时间戳越小，点赞越晚时间戳越大，时间戳天然带着顺序
     * @param id
     * @return
     */
    @Override
    public Result likeBlog(Long id) {
        // 1.获取登录用户
        Long userId = UserHolder.getUser().getId();

        // 2.判断当前登录用户是否已经点赞
        String key = RedisConstants.BLOG_LIKED_KEY + id;
        // 获取sorted set中指定元素的score值
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        if (null == score) {
            // 3.未点赞
            // 3.1.点赞数+1
            boolean isSuccess = update().setSql("liked = liked + 1").eq("id", id).update();
            // 3.2。保存用户到Redis的Set集合
            if (isSuccess) {
                stringRedisTemplate.opsForZSet().add(key, userId.toString(), System.currentTimeMillis());
            }
        } else {
            // 4.已点赞
            // 4.1.点击取消点赞
            boolean isSuccess = update().setSql("liked = liked - 1").eq("id", id).update();
            // 4.2.把用户id从Redis的Set集合中移除
            if (isSuccess) {
                stringRedisTemplate.opsForZSet().remove(key, userId.toString());
            }
        }
        return Result.ok();
    }

    @Override
    public Result queryBlogLikes(Long id) {
        String key = RedisConstants.BLOG_LIKED_KEY + id;
        // 1.查询top5的点赞用户：最先点赞的5个用户 zrange key 0 4
        Set<String> top5 = stringRedisTemplate.opsForZSet().range(key, 0, 4);
        if (CollectionUtil.isEmpty(top5)) {
            return Result.ok(Collections.emptyList());
        }

        // 2.解析出其中的用户id：获取的ids的顺序是正确的
        List<Long> ids = top5.stream().map(userId -> Long.valueOf(userId)).collect(Collectors.toList());
        String idStr = StrUtil.join(",", ids);

        // 3.根据用户id查询用户
        // 注意：ids的顺序是对的，但是查询结果的排序和in的顺序是不一致的。所以我们这里指定下按照ids排序 order by field(id, id1, id2, id3, ...)
        // List<UserDTO> userDTOS = userService.listByIds(ids)
        List<UserDTO> userDTOS = userService.query()
                .in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list()
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        return Result.ok(userDTOS);
    }

    private void queryBlogUser(Blog blog) {
        User user = userService.getById(blog.getUserId());
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());

        // 判断该blog是否已被当前用户点赞过
        UserDTO currentUser = UserHolder.getUser();
        if (null == currentUser) {
            // 用户未登录，无需查询当前用户是否点赞过
            return;
        }
        Double score = stringRedisTemplate.opsForZSet().score(
                RedisConstants.BLOG_LIKED_KEY + blog.getId(),
                currentUser.getId().toString());
        blog.setIsLike(score != null);
    }
}
