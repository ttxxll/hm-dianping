-- 锁的key
local key = KEYS[1]
-- 当前线程标识
local threadId = ARGV[1]

-- 获取锁中的线程标识
local value = redis.call('get', key)
-- 比较线程标识与锁中的标识是否一致
if (value == ARGV[1]) then
	--释放锁 del key
	return redis.call('del', KEYS[1])
end
	return 0