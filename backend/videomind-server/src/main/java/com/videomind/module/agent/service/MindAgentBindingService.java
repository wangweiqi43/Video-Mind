package com.videomind.module.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.videomind.agentclient.AgentClientProperties;
import com.videomind.module.agent.mapper.MindAgentBindingMapper;
import java.net.http.HttpClient;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * @deprecated Use {@link MindAgentOAuthService}. Kept as a source-compatibility bridge.
 */
@Deprecated
public class MindAgentBindingService extends MindAgentOAuthService {

    public MindAgentBindingService(
            AgentClientProperties properties,
            MindAgentBindingMapper mapper,
            TokenCipher cipher,
            StringRedisTemplate redis,
            RedissonClient redisson,
            ObjectMapper objectMapper,
            HttpClient httpClient
    ) {
        super(properties, mapper, cipher, redis, redisson, objectMapper, httpClient);
    }
}
