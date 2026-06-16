package com.videomind.infrastructure.ratelimit;

import com.videomind.common.exception.BizException;
import com.videomind.config.RateLimitProperties;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RateIntervalUnit;
import org.redisson.api.RateType;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class RedissonRateLimitService implements RateLimitService {

    private final RedissonClient redissonClient;
    private final RateLimitProperties rateLimitProperties;

    @Override
    public void acquire(String key, long permitsPerMinute) {
        RRateLimiter limiter = redissonClient.getRateLimiter("ratelimit:" + key);
        limiter.trySetRate(RateType.OVERALL, permitsPerMinute, 1, RateIntervalUnit.MINUTES);
        limiter.expireIfNotSet(Duration.ofSeconds(rateLimitProperties.getTtlSeconds()));
        if (!limiter.tryAcquire()) {
            throw new BizException(429, "请求过于频繁，请稍后再试");
        }
    }
}
