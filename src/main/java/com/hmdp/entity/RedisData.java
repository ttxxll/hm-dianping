package com.hmdp.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 描述：
 *
 * @author txl
 * @date 2022-07-26 22:45
 */
@Data
public class RedisData {

    /**
     * 过期时间
     */
    private LocalDateTime expireTime;

    private Object data;
}
