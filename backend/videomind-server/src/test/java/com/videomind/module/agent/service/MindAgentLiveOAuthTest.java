package com.videomind.module.agent.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.videomind.agentclient.AgentApiClient;
import com.videomind.agentclient.AgentRequestContext;
import com.videomind.module.agent.entity.MindAgentBinding;
import com.videomind.module.agent.mapper.MindAgentBindingMapper;
import com.videomind.module.auth.entity.AppUser;
import com.videomind.module.auth.mapper.AppUserMapper;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
@EnabledIfEnvironmentVariable(named = "VIDEOMIND_LIVE_OAUTH_TEST_USER", matches = ".+")
class MindAgentLiveOAuthTest {

    @Autowired
    private AppUserMapper users;

    @Autowired
    private MindAgentBindingMapper bindings;

    @Autowired
    private MindAgentOAuthService oauth;

    @Autowired
    private AgentApiClient api;

    @Autowired
    private TokenCipher cipher;

    @Test
    void rotatesRealMindAgentRefreshTokenForTemporaryBinding() {
        String username = System.getenv("VIDEOMIND_LIVE_OAUTH_TEST_USER");
        AppUser user = users.selectOne(new LambdaQueryWrapper<AppUser>().eq(AppUser::getUsername, username));
        assertThat(user).as("temporary VideoMind user").isNotNull();

        MindAgentBinding before = binding(user.getId());
        assertThat(before).as("temporary MindAgent binding").isNotNull();
        assertThat(before.getStatus()).isEqualTo(MindAgentOAuthService.ACTIVE);
        String oldAccessCipher = before.getAccessTokenCipher();
        String oldRefreshCipher = before.getRefreshTokenCipher();

        before.setAccessExpiresAt(LocalDateTime.now());
        bindings.updateById(before);
        String accessToken = oauth.accessToken(user.getId());

        MindAgentBinding after = binding(user.getId());
        assertThat(accessToken).isNotBlank();
        assertThat(after.getStatus()).isEqualTo(MindAgentOAuthService.ACTIVE);
        assertThat(after.getAccessTokenCipher()).isNotEqualTo(oldAccessCipher);
        assertThat(after.getRefreshTokenCipher()).isNotEqualTo(oldRefreshCipher);
        assertThat(after.getAccessExpiresAt()).isAfter(LocalDateTime.now().plusMinutes(5));

        String refreshCipherBeforeReplay = after.getRefreshTokenCipher();
        String invalidAccessCipher = cipher.encrypt("deliberately-invalid-access-token");
        after.setAccessTokenCipher(invalidAccessCipher);
        after.setAccessExpiresAt(LocalDateTime.now().plusMinutes(10));
        bindings.updateById(after);

        JsonNode userInfo = api.get("/v1/userinfo", AgentRequestContext.of(
                "videomind", user.getId(), "live-oauth-auth-replay", UUID.randomUUID().toString()));

        MindAgentBinding replayed = binding(user.getId());
        assertThat(userInfo.path("sub").asText()).isEqualTo(replayed.getMindagentSubject());
        assertThat(replayed.getStatus()).isEqualTo(MindAgentOAuthService.ACTIVE);
        assertThat(replayed.getAccessTokenCipher()).isNotEqualTo(invalidAccessCipher);
        assertThat(replayed.getRefreshTokenCipher()).isNotEqualTo(refreshCipherBeforeReplay);

    }

    private MindAgentBinding binding(Long userId) {
        return bindings.selectOne(new LambdaQueryWrapper<MindAgentBinding>()
                .eq(MindAgentBinding::getUserId, userId));
    }
}
