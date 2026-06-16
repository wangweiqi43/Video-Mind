package com.videomind.infrastructure.ratelimit;

public interface RateLimitService {

    void acquire(String key, long permitsPerMinute);
}

