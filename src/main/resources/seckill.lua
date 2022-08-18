-- 创建一个消费者组group1，从消息队列stream.orders的第一条消息开始消费。如果队列不存在则创建
-- XGROUP CREATE stream.orders group1 0 MKSTREAM

-- 优惠券id
local voucherId = ARGV[1]
-- 用户id
local userId = ARGV[2]
-- 订单id
local orderId = ARGV[3]
-- 消息队列的key
local key = KEYS[1]

-- 库存key
local stockKey = 'seckill:stock:' .. voucherId
-- 订单key：记录有哪些用户下了这个优惠券了
local orderKey = 'seckill:order:' .. voucherId

-- 1.判断库存是否充足
if (tonumber(redis.call('get', stockKey)) <= 0) then
    -- 库存不足，返回1
    return 1
end

-- 2.判断用户是否下单 SISMEMBER orderKey userId Set集合中是否有这个用户
if (redis.call('sismember', orderKey, userId) == 1) then
    -- 存在，重复下单，返回2
    return 2
end

-- 3.下单：扣库存，保存用户
redis.call('incrby', stockKey, -1)
redis.call('sadd', orderKey, userId)

-- 4.发送消息到队列中：XADD stream.order * k1 v1 k2 v2 ... *表示redis来生成消息的id
redis.call('xadd', key, '*', 'userId', userId, 'voucherId', voucherId, 'orderId', orderId)

-- 4.下单成功返回0
return 0