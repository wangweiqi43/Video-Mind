package com.videomind.module.agent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.videomind.agentclient.AgentClientProperties;
import com.videomind.common.exception.BizException;
import com.videomind.module.agent.entity.MindAgentBinding;
import com.videomind.module.agent.mapper.MindAgentBindingMapper;
import com.videomind.module.auth.AuthProperties;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class MindAgentOAuthServiceTest {

    private HttpServer server;
    private AgentClientProperties properties;
    private MindAgentBindingMapper mapper;
    private TokenCipher cipher;
    private StringRedisTemplate redis;
    private ValueOperations<String, String> values;
    private RedissonClient redisson;
    private RLock lock;
    private AtomicInteger tokenRequests;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        properties = new AgentClientProperties();
        properties.setEnabled(true);
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setFrontendUrl("http://localhost:5174");
        properties.setOauthClientId("videomind");
        properties.setOauthClientSecret("oauth-secret");
        properties.setOauthRedirectUri("http://localhost:8080/api/integrations/mindagent/callback");
        mapper = mock(MindAgentBindingMapper.class);
        redis = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        redisson = mock(RedissonClient.class);
        lock = mock(RLock.class);
        when(redisson.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock(5, 30, java.util.concurrent.TimeUnit.SECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        AuthProperties auth = new AuthProperties();
        auth.setTokenEncryptionKey("test-token-encryption-key");
        cipher = new TokenCipher(auth);
        tokenRequests = new AtomicInteger();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void authorizationUsesS256AndStoresOneTimeStateForTenMinutes() {
        String url = service().authorizationUrl(7L);
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> saved = ArgumentCaptor.forClass(String.class);
        verify(values).set(key.capture(), saved.capture(), org.mockito.ArgumentMatchers.eq(Duration.ofMinutes(10)));

        Map<String, String> query = query(url);
        String verifier = saved.getValue().split("\\|", 2)[1];
        assertThat(saved.getValue()).startsWith("7|");
        assertThat(key.getValue()).isEqualTo("mindagent:oauth:state:" + query.get("state"));
        assertThat(query.get("code_challenge_method")).isEqualTo("S256");
        assertThat(query.get("code_challenge")).isEqualTo(MindAgentOAuthService.pkceChallenge(verifier));
    }

    @Test
    void stateCanOnlyBeConsumedOnceAndBelongsToCurrentUser() {
        when(values.getAndDelete("mindagent:oauth:state:state-1")).thenReturn("7|verifier", (String) null);
        MindAgentOAuthService service = service();

        service.rejectAuthorization(7L, "state-1");
        assertThatThrownBy(() -> service.rejectAuthorization(7L, "state-1"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("已过期");

        when(values.getAndDelete("mindagent:oauth:state:state-2")).thenReturn("8|verifier");
        assertThatThrownBy(() -> service.rejectAuthorization(7L, "state-2"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不属于当前用户");
    }

    @Test
    void statusDistinguishesUnboundActiveAndReauthorizationRequired() {
        when(mapper.selectOne(any())).thenReturn(null);
        assertThat(service().status(7L)).containsEntry("status", "UNBOUND")
                .containsEntry("bound", false)
                .containsEntry("rebindRequired", false);

        MindAgentBinding binding = activeBinding("access", "refresh", LocalDateTime.now().plusMinutes(10));
        binding.setStatus(MindAgentOAuthService.REAUTH_REQUIRED);
        when(mapper.selectOne(any())).thenReturn(binding);
        assertThat(service().status(7L)).containsEntry("status", MindAgentOAuthService.REAUTH_REQUIRED)
                .containsEntry("bound", false)
                .containsEntry("rebindRequired", true)
                .containsEntry("username", "mind-user");
    }

    @Test
    void refreshesEarlyAndPersistsRotatedRefreshToken() {
        MindAgentBinding binding = activeBinding("old-access", "old-refresh", LocalDateTime.now().plusSeconds(20));
        when(mapper.selectOne(any())).thenReturn(binding);
        server.createContext("/oauth2/token", exchange -> {
            tokenRequests.incrementAndGet();
            respond(exchange, 200,
                    "{\"access_token\":\"new-access\",\"refresh_token\":\"new-refresh\",\"expires_in\":900,\"scope\":\"profile\"}");
        });
        server.start();

        assertThat(service().accessToken(7L)).isEqualTo("new-access");
        assertThat(tokenRequests).hasValue(1);
        assertThat(cipher.decrypt(binding.getRefreshTokenCipher())).isEqualTo("new-refresh");
        assertThat(binding.getScopes()).isEqualTo("profile");
        assertThat(binding.getStatus()).isEqualTo(MindAgentOAuthService.ACTIVE);
        verify(mapper).updateById(binding);
    }

    @Test
    void concurrentUnauthorizedRequestReusesTokenAlreadyRefreshedByPeer() {
        MindAgentBinding binding = activeBinding("peer-new-access", "peer-new-refresh", LocalDateTime.now().plusMinutes(10));
        when(mapper.selectOne(any())).thenReturn(binding);

        assertThat(service().refreshAfterUnauthorized(7L, "rejected-old-access")).isEqualTo("peer-new-access");
        verify(mapper, never()).updateById(any(MindAgentBinding.class));
    }

    @Test
    void permanentRefreshFailureMarksBindingForReauthorization() {
        MindAgentBinding binding = activeBinding("old-access", "invalid-refresh", LocalDateTime.now());
        when(mapper.selectOne(any())).thenReturn(binding);
        server.createContext("/oauth2/token", exchange ->
                respond(exchange, 401, "{\"code\":\"INVALID_REFRESH_TOKEN\",\"message\":\"secret detail\"}"));
        server.start();

        assertThatThrownBy(() -> service().accessToken(7L))
                .isInstanceOf(BizException.class)
                .hasMessage("MindAgent 授权已失效，请重新绑定")
                .hasMessageNotContaining("secret detail");
        assertThat(binding.getStatus()).isEqualTo(MindAgentOAuthService.REAUTH_REQUIRED);
        verify(mapper).updateById(binding);
    }

    @Test
    void transientRefreshFailureKeepsBindingActive() {
        MindAgentBinding binding = activeBinding("old-access", "refresh", LocalDateTime.now());
        when(mapper.selectOne(any())).thenReturn(binding);
        server.createContext("/oauth2/token", exchange ->
                respond(exchange, 503, "{\"code\":\"TEMPORARILY_UNAVAILABLE\",\"message\":\"secret detail\"}"));
        server.start();

        assertThatThrownBy(() -> service().accessToken(7L))
                .isInstanceOf(BizException.class)
                .hasMessage("MindAgent 授权服务暂时不可用")
                .hasMessageNotContaining("secret detail");
        assertThat(binding.getStatus()).isEqualTo(MindAgentOAuthService.ACTIVE);
        verify(mapper, never()).updateById(any(MindAgentBinding.class));
    }

    @Test
    void invalidSuccessfulTokenResponseDoesNotOverwriteStoredTokens() {
        MindAgentBinding binding = activeBinding("old-access", "old-refresh", LocalDateTime.now());
        when(mapper.selectOne(any())).thenReturn(binding);
        server.createContext("/oauth2/token", exchange ->
                respond(exchange, 200, "{\"access_token\":\"incomplete\",\"expires_in\":900}"));
        server.start();

        assertThatThrownBy(() -> service().accessToken(7L))
                .isInstanceOf(BizException.class)
                .hasMessage("MindAgent 令牌响应无效");
        assertThat(cipher.decrypt(binding.getAccessTokenCipher())).isEqualTo("old-access");
        assertThat(cipher.decrypt(binding.getRefreshTokenCipher())).isEqualTo("old-refresh");
        assertThat(binding.getStatus()).isEqualTo(MindAgentOAuthService.ACTIVE);
        verify(mapper, never()).updateById(any(MindAgentBinding.class));
    }

    private MindAgentOAuthService service() {
        return new MindAgentOAuthService(properties, mapper, cipher, redis, redisson,
                new ObjectMapper(), HttpClient.newHttpClient());
    }

    private MindAgentBinding activeBinding(String access, String refresh, LocalDateTime expiresAt) {
        MindAgentBinding binding = new MindAgentBinding();
        binding.setId(1L);
        binding.setUserId(7L);
        binding.setMindagentSubject("subject-1");
        binding.setMindagentUsername("mind-user");
        binding.setAccessTokenCipher(cipher.encrypt(access));
        binding.setRefreshTokenCipher(cipher.encrypt(refresh));
        binding.setScopes(MindAgentOAuthService.SCOPES);
        binding.setAccessExpiresAt(expiresAt);
        binding.setStatus(MindAgentOAuthService.ACTIVE);
        return binding;
    }

    private Map<String, String> query(String url) {
        return Arrays.stream(URI.create(url).getRawQuery().split("&"))
                .map(value -> value.split("=", 2))
                .collect(Collectors.toMap(
                        value -> URLDecoder.decode(value[0], StandardCharsets.UTF_8),
                        value -> URLDecoder.decode(value[1], StandardCharsets.UTF_8)
                ));
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
