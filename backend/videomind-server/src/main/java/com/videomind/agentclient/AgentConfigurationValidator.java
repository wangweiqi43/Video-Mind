package com.videomind.agentclient;

import java.net.URI;
import java.util.Locale;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class AgentConfigurationValidator implements InitializingBean {

    private final AgentClientProperties properties;

    public AgentConfigurationValidator(AgentClientProperties properties) {
        this.properties = properties;
    }

    @Override
    public void afterPropertiesSet() {
        validate();
    }

    public void validate() {
        positive("AGENT_PLATFORM_CONNECT_TIMEOUT_SECONDS", properties.getConnectTimeoutSeconds());
        positive("AGENT_PLATFORM_READ_TIMEOUT_SECONDS", properties.getReadTimeoutSeconds());
        positive("AGENT_PRESIGNED_URL_EXPIRY_SECONDS", properties.getPresignedUrlExpirySeconds());
        positive("AGENT_TASK_POLL_INTERVAL_SECONDS", properties.getTaskPollIntervalSeconds());
        if (properties.getMaxRetries() < 0) {
            invalid("AGENT_PLATFORM_MAX_RETRIES", "不能小于 0");
        }

        boolean childEnabled = properties.isIngestEnabled()
                || properties.isChatEnabled()
                || properties.isWebSearchEnabled()
                || properties.isAdvancedReportEnabled()
                || properties.isPresentationEnabled();
        if (childEnabled && !properties.isEnabled()) {
            invalid("VIDEOMIND_AGENT_ENABLED", "子能力开启时主开关必须为 true");
        }
        if (properties.isWebSearchEnabled() && !properties.isChatEnabled()) {
            invalid("VIDEOMIND_AGENT_WEB_SEARCH_ENABLED", "启用联网搜索前必须启用高级聊天");
        }
        if (!properties.isEnabled()) {
            return;
        }

        httpUri("AGENT_PLATFORM_BASE_URL", properties.getBaseUrl());
        httpUri("AGENT_PLATFORM_FRONTEND_URL", properties.getFrontendUrl());
        httpUri("AGENT_PLATFORM_OAUTH_REDIRECT_URI", properties.getOauthRedirectUri());
        required("AGENT_PLATFORM_OAUTH_CLIENT_ID", properties.getOauthClientId());
        secret("AGENT_PLATFORM_OAUTH_CLIENT_SECRET", properties.getOauthClientSecret());
        secret("AGENT_PLATFORM_WEBHOOK_SECRET", properties.getWebhookSecret());
    }

    private void positive(String name, int value) {
        if (value <= 0) {
            invalid(name, "必须大于 0");
        }
    }

    private void required(String name, String value) {
        if (!StringUtils.hasText(value)) {
            invalid(name, "不能为空");
        }
    }

    private void secret(String name, String value) {
        required(name, value);
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("replace-") || normalized.startsWith("your_")
                || normalized.startsWith("your-") || normalized.contains("<") || normalized.contains(">")) {
            invalid(name, "不能使用示例占位值");
        }
    }

    private void httpUri(String name, String value) {
        required(name, value);
        try {
            URI uri = URI.create(value);
            String scheme = uri.getScheme();
            if (!uri.isAbsolute() || !StringUtils.hasText(uri.getHost())
                    || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
                invalid(name, "必须是绝对 HTTP(S) 地址");
            }
        } catch (IllegalArgumentException error) {
            invalid(name, "不是合法 URI");
        }
    }

    private void invalid(String name, String reason) {
        throw new IllegalStateException("Agent 配置无效：" + name + " " + reason);
    }
}
