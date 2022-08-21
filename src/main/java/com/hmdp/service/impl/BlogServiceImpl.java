package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.constant.RedisConstants;
import com.hmdp.constant.RespConstant;
import com.hmdp.constant.SystemConstants;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
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

    @Resource
    private IFollowService followService;

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

    @Override
    public Result saveBlog(Blog blog) {

        // 获取登录用户
        UserDTO user  = UserHolder.getUser();
        blog.setUserId(user.getId());

        // 保存探店博文
        boolean isSuccess = save(blog);
        if (!isSuccess) {
            return Result.fail(RespConstant.MESSAGE_SAVE_BLOG_ERROR);
        }

        // 查询所有的粉丝 select user_id from tb_follow where follow_user_id = ?
        List<Follow> follows = followService.query().eq("follow_user_id", user.getId()).list();

        // 推送笔记id给所有粉丝
        for (Follow follow : follows) {
            // 获取粉丝id
            Long followId = follow.getUserId();
            // 推送：将博客id存到粉丝的收件箱中。这里每个粉丝都有一个SortedSet，里面存放着推送过来的博客id，socre是时间戳。
            String key = RedisConstants.FEED_KEY + followId;
            stringRedisTemplate.opsForZSet().add(key, blog.getId().toString(), System.currentTimeMillis());
        }

        // 返回id
        return Result.ok(blog.getId());
    }

    /**
     * 如下一个SortedSet，pageSize是2
     *  (id10 10) (id9 9) (id8-1 8) (id8-2 8) (id7 7) (id6-1 6) (id6-2 6) (id5 5) (id4 4) (id3 3) (id2 2) (id1-1 1) (id1-2 1)
     * ZREVRANGEBYSCORE key 100 0 LIMIT 0 2：查询score范围在[100, 0]之间的元素，第0个开始，查询2个：(id10 10) (id9 9)
     * ZREVRANGEBYSCORE key 9 0 LIMIT 1 2：查询score范围在[9, 0]之间的元素，第1个开始，查询2个：(id8-1 8) (id8-2 8)
     *  注意，此时传给下一次查询的offset是2
     * ZREVRANGEBYSCORE key 8 0 LIMIT 2 2：查询score范围在[9, 0]之间的元素，第2个开始，查询2个：(id7 7) (id6-1 6)
     *
     * @param max
     * @param offset
     * @return
     */
    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {
        // 1.获取当前用户
        Long userId = UserHolder.getUser().getId();

        // 2.查询收件箱：ZREVRANGEBYSCORE key Max Min LIMIT offset count
        // RedisConstants.FEED_KEY + userId 相当于邮箱，每个用户都有一个ZSet，里面存着推送过来的博客id
        String key = RedisConstants.FEED_KEY + userId;
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet()
                .reverseRangeByScoreWithScores(key, 0, max, offset, 2);

        // 3.非空判断
        if (CollectionUtil.isEmpty(typedTuples)) {
            return Result.ok();
        }

        // 4.解析数据： blogId, minTime（最小score），offset
        // ArrayList<Long> ids = new ArrayList<>(typedTuples.size());
        ArrayList<String> ids = new ArrayList<>(typedTuples.size());
        long minTime = 0;
        int os = 1;
        for (ZSetOperations.TypedTuple<String> typedTuple : typedTuples) { // 假设score是 5 4 4 2 2
            // 4.1.获取id
            // ids.add(Long.valueOf(typedTuple.getValue()));
            ids.add(typedTuple.getValue());
            // 4.2.获取分数（时间戳）
            long time = typedTuple.getScore().longValue();
            if (time == minTime) {
                os++;
            } else {
                // minTime != time说明此时最小值是time，因为我们是倒序查的，遍历的当前元素的score就是最小的。
                minTime = time;
                // 重置offset：第一次查是从第0个开始 | 后续查询时，按照上一次的结果中与最小值一样的元素个数，为了跳过score一样的元素
                os = 1;
            }
        }
        // offset：第一次查询时参数为0，后续为按照上一次的结果中与最小值一样的元素个数。
        // 即如果score都是不同的，那么偏移量后续都从1开始即可。如果上次查询的最小score有多个，那么偏移量要加上os
        os = minTime == max ? os : os + offset;

        // 5.根据id查询blog
        String idStr = StrUtil.join(",", ids);
        List<Blog> blogs = query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list();
        for (Blog blog : blogs) {
            // 5.1.查询blog有关的用户
            queryBlogUser(blog);
        }

        // 6.封装并返回
        ScrollResult result = new ScrollResult();
        result.setList(blogs);
        result.setOffset(os);
        result.setMinTime(minTime);
        return Result.ok(result);
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
