-- 修正后的秒杀Lua脚本
-- 参数说明：KEYS无，ARGV[1]=优惠券id，ARGV[2]=用户id
local voucherId = ARGV[1]  -- 原脚本写的voucher，修正为voucherId
local userId = ARGV[2]

local stockKey = "seckill:stock:" .. voucherId  -- 原脚本重复定义，且缺冒号
local orderKey = "seckill:order:" .. voucherId  -- 原脚本写的stockKey，修正为orderKey

-- 1. 判断库存是否充足
if tonumber(redis.call('get', stockKey)) <= 0 then
	return 1  -- 1=库存不足
end

-- 2. 判断用户是否已下单（原脚本uuserId写错，修正为userId）
if redis.call('sismember', orderKey, userId) == 1 then
	return 2  -- 2=用户已下单
end

-- 3. 扣减库存 + 记录下单用户
redis.call('incrby', stockKey, -1)
redis.call('sadd', orderKey, userId)

return 0  -- 0=秒杀成功（建议补充这个返回值，逻辑更完整）