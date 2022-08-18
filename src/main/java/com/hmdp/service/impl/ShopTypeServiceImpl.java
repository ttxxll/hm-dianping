package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.constant.RedisConstants;
import com.hmdp.constant.RespConstant;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryShopType() {

        // 1.先从缓存查询
        String key = RedisConstants.CACHE_SHOP_TYPE_KEY;
        String shopTypeJson = stringRedisTemplate.opsForValue().get(key);

        // 2.查询到的话，直接返回
        if (StrUtil.isNotBlank(shopTypeJson)) {
            List<ShopType> shopTypeList = JSONUtil.toList(shopTypeJson, ShopType.class);
            return Result.ok(shopTypeList);
        }

        // 3.Redis没有，从数据库查
        List<ShopType> shopTypeList = this.query().orderByAsc("sort").list();
        if (null == shopTypeList) {
            return Result.fail(RespConstant.MESSAGE_SYSTEM_ERROR);
        }

        // 4.存入缓存
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shopTypeList),
                RedisConstants.CACHE_SHOP_TYPE_TTL, TimeUnit.MINUTES);

        return Result.ok(shopTypeList);
    }
}
