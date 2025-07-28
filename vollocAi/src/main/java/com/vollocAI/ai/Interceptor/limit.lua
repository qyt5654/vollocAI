-- KEYS[1]: 限流的 key，比如 user:123
-- ARGV[1]: 限流的时间窗口（秒）
-- ARGV[2]: 最大请求数

local key = KEYS[1]
local window = tonumber(ARGV[1])
local limit = tonumber(ARGV[2])

-- 当前请求数
local current = tonumber(redis.call("GET", key) or "0")

if current + 1 > limit then
    return 0
else
    current = redis.call("INCR", key)
    if current == 1 then
        redis.call("EXPIRE", key, window)
    end
    return 1
end
