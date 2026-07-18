package com.videomind.module.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.videomind.agentclient.AgentClientProperties;
import com.videomind.common.exception.BizException;
import com.videomind.module.agent.entity.MindAgentBinding;
import com.videomind.module.agent.mapper.MindAgentBindingMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class MindAgentOAuthService {

    static final String ACTIVE = "ACTIVE";
    static final String REAUTH_REQUIRED = "REAUTH_REQUIRED";
    static final String SCOPES = "openid profile knowledge:read knowledge:write chat:read chat:write tasks:read";
    private static final Duration STATE_TTL = Duration.ofMinutes(10);
    private static final Duration REFRESH_AHEAD = Duration.ofMinutes(1);
    private static final Set<String> PERMANENT_REFRESH_ERRORS = Set.of(
            "INVALID_REFRESH_TOKEN", "INVALID_CLIENT", "USER_DISABLED"
    );

    private final AgentClientProperties properties;
    private final MindAgentBindingMapper mapper;
    private final TokenCipher cipher;
    private final StringRedisTemplate redis;
    private final RedissonClient redisson;
    private final ObjectMapper json;
    private final HttpClient http;
    private final SecureRandom random = new SecureRandom();

    public MindAgentOAuthService(
            AgentClientProperties properties,
            MindAgentBindingMapper mapper,
            TokenCipher cipher,
            StringRedisTemplate redis,
            RedissonClient redisson,
            ObjectMapper json,
            HttpClient http
    ) {
        this.properties = properties;
        this.mapper = mapper;
        this.cipher = cipher;
        this.redis = redis;
        this.redisson = redisson;
        this.json = json;
        this.http = http;
    }

    public Map<String, Object> status(Long userId) {
        MindAgentBinding binding = findAny(userId);
        Map<String, Object> result = new LinkedHashMap<>();
        if (binding == null) {
            result.put("bound", false);
            result.put("rebindRequired", false);
            result.put("status", "UNBOUND");
            return result;
        }
        boolean active = ACTIVE.equals(binding.getStatus());
        result.put("bound", active);
        result.put("rebindRequired", REAUTH_REQUIRED.equals(binding.getStatus()));
        result.put("status", binding.getStatus());
        if (StringUtils.hasText(binding.getMindagentSubject())) {
            result.put("subject", binding.getMindagentSubject());
        }
        if (StringUtils.hasText(binding.getMindagentUsername())) {
            result.put("username", binding.getMindagentUsername());
        }
        return result;
    }

    public String authorizationUrl(Long userId) {
        if (!properties.isEnabled()) {
            throw new BizException(503, "MindAgent 功能未启用");
        }
        String state = randomToken(32);
        String verifier = randomToken(48);
        redis.opsForValue().set(stateKey(state), userId + "|" + verifier, STATE_TTL);
        return trimTrailingSlash(properties.getFrontendUrl()) + "/oauth/authorize"
                + "?response_type=code"
                + "&client_id=" + encode(properties.getOauthClientId())
                + "&redirect_uri=" + encode(properties.getOauthRedirectUri())
                + "&scope=" + encode(SCOPES)
                + "&state=" + encode(state)
                + "&code_challenge=" + encode(pkceChallenge(verifier))
                + "&code_challenge_method=S256";
    }

    @Transactional
    public void callback(Long currentUserId, String code, String state) {
        OAuthState oauthState = consumeState(currentUserId, state);
        if (!StringUtils.hasText(code)) {
            throw new BizException(400, "MindAgent 未返回授权码");
        }
        TokenPayload payload;
        try {
            JsonNode token = requestToken(Map.of(
                    "grant_type", "authorization_code",
                    "client_id", properties.getOauthClientId(),
                    "client_secret", properties.getOauthClientSecret(),
                    "code", code,
                    "redirect_uri", properties.getOauthRedirectUri(),
                    "code_verifier", oauthState.verifier()
            ));
            payload = validateTokenPayload(token);
        } catch (OAuthRequestException error) {
            throw error.asBizException();
        }
        JsonNode userInfo = getUserInfo(payload.accessToken());
        String subject = userInfo.path("sub").asText();
        if (!StringUtils.hasText(subject)) {
            revoke(payload.refreshToken());
            throw new BizException(502, "MindAgent 用户信息响应无效");
        }

        MindAgentBinding other = mapper.selectOne(new LambdaQueryWrapper<MindAgentBinding>()
                .eq(MindAgentBinding::getMindagentSubject, subject)
                .ne(MindAgentBinding::getUserId, currentUserId));
        if (other != null) {
            revoke(payload.refreshToken());
            throw new BizException(409, "该 MindAgent 账号已绑定其他 VideoMind 账号");
        }

        MindAgentBinding binding = findAny(currentUserId);
        if (binding == null) {
            binding = new MindAgentBinding();
            binding.setUserId(currentUserId);
            binding.setCreatedAt(LocalDateTime.now());
        } else {
            revokeSafely(binding.getRefreshTokenCipher());
        }
        applyToken(binding, payload);
        binding.setMindagentSubject(subject);
        binding.setMindagentUsername(userInfo.path("preferred_username").asText(null));
        binding.setStatus(ACTIVE);
        binding.setUpdatedAt(LocalDateTime.now());
        if (binding.getId() == null) {
            mapper.insert(binding);
        } else {
            mapper.updateById(binding);
        }
    }

    public void rejectAuthorization(Long currentUserId, String state) {
        consumeState(currentUserId, state);
    }

    public String accessToken(Long userId) {
        MindAgentBinding binding = requireActive(userId);
        String accessToken = cipher.decrypt(binding.getAccessTokenCipher());
        if (!needsRefresh(binding)) {
            return accessToken;
        }
        return refreshToken(userId, null, false);
    }

    public String refreshAfterUnauthorized(Long userId, String rejectedAccessToken) {
        return refreshToken(userId, rejectedAccessToken, true);
    }

    @Transactional
    public void unlink(Long userId) {
        MindAgentBinding binding = findAny(userId);
        if (binding == null) {
            return;
        }
        revokeSafely(binding.getRefreshTokenCipher());
        mapper.deleteById(binding.getId());
    }

    private String refreshToken(Long userId, String rejectedAccessToken, boolean force) {
        RLock lock = redisson.getLock("mindagent:token:refresh:" + userId);
        try {
            if (!lock.tryLock(5, 30, TimeUnit.SECONDS)) {
                throw new BizException(503, "MindAgent 令牌刷新繁忙，请稍后重试");
            }
            MindAgentBinding binding = requireActive(userId);
            String currentAccessToken = cipher.decrypt(binding.getAccessTokenCipher());
            if (StringUtils.hasText(rejectedAccessToken) && !currentAccessToken.equals(rejectedAccessToken)) {
                return currentAccessToken;
            }
            if (!force && !needsRefresh(binding)) {
                return currentAccessToken;
            }
            try {
                JsonNode token = requestToken(Map.of(
                        "grant_type", "refresh_token",
                        "client_id", properties.getOauthClientId(),
                        "client_secret", properties.getOauthClientSecret(),
                        "refresh_token", cipher.decrypt(binding.getRefreshTokenCipher())
                ));
                applyToken(binding, validateTokenPayload(token));
                binding.setStatus(ACTIVE);
                binding.setUpdatedAt(LocalDateTime.now());
                mapper.updateById(binding);
                return cipher.decrypt(binding.getAccessTokenCipher());
            } catch (OAuthRequestException error) {
                if (PERMANENT_REFRESH_ERRORS.contains(error.errorCode())) {
                    binding.setStatus(REAUTH_REQUIRED);
                    binding.setUpdatedAt(LocalDateTime.now());
                    mapper.updateById(binding);
                    throw new BizException(401, "MindAgent 授权已失效，请重新绑定");
                }
                throw error.asBizException();
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new BizException(503, "MindAgent 令牌刷新被中断");
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private void applyToken(MindAgentBinding binding, TokenPayload payload) {
        binding.setAccessTokenCipher(cipher.encrypt(payload.accessToken()));
        binding.setRefreshTokenCipher(cipher.encrypt(payload.refreshToken()));
        binding.setScopes(payload.scope());
        binding.setAccessExpiresAt(LocalDateTime.now().plusSeconds(payload.expiresIn()));
    }

    private boolean needsRefresh(MindAgentBinding binding) {
        return binding.getAccessExpiresAt() == null
                || !binding.getAccessExpiresAt().isAfter(LocalDateTime.now().plus(REFRESH_AHEAD));
    }

    private MindAgentBinding requireActive(Long userId) {
        MindAgentBinding binding = findAny(userId);
        if (binding == null) {
            throw new BizException(401, "MINDAGENT_BINDING_REQUIRED");
        }
        if (!ACTIVE.equals(binding.getStatus())) {
            throw new BizException(401, "MINDAGENT_REAUTH_REQUIRED");
        }
        return binding;
    }

    private MindAgentBinding findAny(Long userId) {
        return mapper.selectOne(new LambdaQueryWrapper<MindAgentBinding>()
                .eq(MindAgentBinding::getUserId, userId));
    }

    private OAuthState consumeState(Long currentUserId, String state) {
        if (!StringUtils.hasText(state)) {
            throw new BizException(400, "绑定状态缺失，请重新发起");
        }
        String saved = redis.opsForValue().getAndDelete(stateKey(state));
        if (!StringUtils.hasText(saved)) {
            throw new BizException(400, "绑定状态已过期，请重新发起");
        }
        String[] parts = saved.split("\\|", 2);
        if (parts.length != 2) {
            throw new BizException(400, "绑定状态无效，请重新发起");
        }
        Long stateUserId;
        try {
            stateUserId = Long.valueOf(parts[0]);
        } catch (NumberFormatException error) {
            throw new BizException(400, "绑定状态无效，请重新发起");
        }
        if (!stateUserId.equals(currentUserId)) {
            throw new BizException(403, "绑定请求不属于当前用户");
        }
        return new OAuthState(parts[1]);
    }

    private JsonNode requestToken(Map<String, String> form) {
        try {
            String body = form.entrySet().stream()
                    .map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
                    .collect(Collectors.joining("&"));
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl() + "/oauth2/token"))
                    .timeout(Duration.ofSeconds(properties.getReadTimeoutSeconds()))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw tokenError(response.statusCode(), response.body());
            }
            return json.readTree(response.body());
        } catch (OAuthRequestException error) {
            throw error;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new OAuthRequestException("OAUTH_INTERRUPTED", 503, "MindAgent 授权请求被中断", true);
        } catch (IOException | IllegalArgumentException error) {
            throw new OAuthRequestException("OAUTH_NETWORK_ERROR", 503, "MindAgent 授权服务暂时不可用", true);
        }
    }

    private OAuthRequestException tokenError(int status, String body) {
        String code = "OAUTH_HTTP_" + status;
        try {
            JsonNode error = json.readTree(body);
            code = error.path("errorCode").asText(error.path("code").asText(code));
        } catch (Exception ignored) {
            // Return a stable local error without exposing the upstream response body.
        }
        boolean retryable = status == 408 || status == 429 || status >= 500;
        return new OAuthRequestException(code, retryable ? 503 : 502,
                retryable ? "MindAgent 授权服务暂时不可用" : "MindAgent 授权失败", retryable);
    }

    private TokenPayload validateTokenPayload(JsonNode token) {
        String accessToken = token.path("access_token").asText();
        String refreshToken = token.path("refresh_token").asText();
        long expiresIn = token.path("expires_in").asLong(0);
        if (!StringUtils.hasText(accessToken) || !StringUtils.hasText(refreshToken) || expiresIn <= 0) {
            throw new OAuthRequestException("OAUTH_INVALID_RESPONSE", 502, "MindAgent 令牌响应无效", false);
        }
        return new TokenPayload(accessToken, refreshToken, expiresIn, token.path("scope").asText(SCOPES));
    }

    private JsonNode getUserInfo(String accessToken) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl() + "/v1/userinfo"))
                    .timeout(Duration.ofSeconds(properties.getReadTimeoutSeconds()))
                    .header("Authorization", "Bearer " + accessToken)
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new BizException(502, "MindAgent 用户信息读取失败");
            }
            return json.readTree(response.body());
        } catch (BizException error) {
            throw error;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new BizException(503, "MindAgent 用户信息请求被中断");
        } catch (IOException | IllegalArgumentException error) {
            throw new BizException(503, "MindAgent 用户信息服务暂时不可用");
        }
    }

    private void revokeSafely(String encryptedRefreshToken) {
        if (!StringUtils.hasText(encryptedRefreshToken)) {
            return;
        }
        try {
            revoke(cipher.decrypt(encryptedRefreshToken));
        } catch (RuntimeException ignored) {
            // Revocation is best effort during unlink/rebind; local state remains authoritative.
        }
    }

    private void revoke(String refreshToken) {
        try {
            String body = "token=" + encode(refreshToken);
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl() + "/oauth2/revoke"))
                    .timeout(Duration.ofSeconds(properties.getConnectTimeoutSeconds()))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            http.send(request, HttpResponse.BodyHandlers.discarding());
        } catch (Exception ignored) {
            // Best effort. Never expose or log the token.
        }
    }

    private String baseUrl() {
        return trimTrailingSlash(properties.getBaseUrl());
    }

    private String trimTrailingSlash(String value) {
        return value.replaceAll("/+$", "");
    }

    private String stateKey(String state) {
        return "mindagent:oauth:state:" + state;
    }

    private String randomToken(int bytes) {
        byte[] value = new byte[bytes];
        random.nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    static String pkceChallenge(String verifier) {
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII))
            );
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private record OAuthState(String verifier) {
    }

    private record TokenPayload(String accessToken, String refreshToken, long expiresIn, String scope) {
    }

    private static final class OAuthRequestException extends RuntimeException {
        private final String errorCode;
        private final int status;
        private final String safeMessage;
        @SuppressWarnings("unused")
        private final boolean retryable;

        private OAuthRequestException(String errorCode, int status, String safeMessage, boolean retryable) {
            super(safeMessage);
            this.errorCode = errorCode;
            this.status = status;
            this.safeMessage = safeMessage;
            this.retryable = retryable;
        }

        private String errorCode() {
            return errorCode;
        }

        private BizException asBizException() {
            return new BizException(status, safeMessage);
        }
    }
}
