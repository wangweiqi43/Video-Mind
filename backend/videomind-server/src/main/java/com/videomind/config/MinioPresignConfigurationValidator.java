package com.videomind.config;

import com.videomind.agentclient.AgentClientProperties;
import java.net.URI;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class MinioPresignConfigurationValidator implements InitializingBean {

    private final AgentClientProperties agent;
    private final MinioProperties minio;

    public MinioPresignConfigurationValidator(AgentClientProperties agent, MinioProperties minio) {
        this.agent = agent;
        this.minio = minio;
    }

    @Override
    public void afterPropertiesSet() {
        if (!agent.isEnabled() || !agent.isIngestEnabled()) return;
        String endpoint = minio.getPresignEndpoint();
        if (!StringUtils.hasText(endpoint)) {
            throw invalid("不能为空");
        }
        try {
            URI uri = URI.create(endpoint);
            if (!uri.isAbsolute() || !StringUtils.hasText(uri.getHost())
                    || !("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))) {
                throw invalid("必须是绝对 HTTP(S) 地址");
            }
        } catch (IllegalArgumentException error) {
            throw invalid("不是合法 URI");
        }
    }

    private IllegalStateException invalid(String reason) {
        return new IllegalStateException("Agent 配置无效：MINIO_PRESIGN_ENDPOINT " + reason);
    }
}
