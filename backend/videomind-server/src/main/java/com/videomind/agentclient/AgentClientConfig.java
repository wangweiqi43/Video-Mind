package com.videomind.agentclient;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(AgentClientProperties.class)
public class AgentClientConfig {

    @Bean
    public HttpClient agentHttpClient(AgentClientProperties properties) {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(properties.getConnectTimeoutSeconds()))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }
}
