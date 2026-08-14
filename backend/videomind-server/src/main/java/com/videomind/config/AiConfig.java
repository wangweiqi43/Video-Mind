package com.videomind.config;

import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(AiProperties.class)
public class AiConfig {

    @Bean
    public RestClient aiRestClient(RestClient.Builder builder, AiProperties aiProperties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(30));
        requestFactory.setReadTimeout(Duration.ofSeconds(resolveReadTimeout(aiProperties)));
        return builder.requestFactory(requestFactory).build();
    }

    private long resolveReadTimeout(AiProperties aiProperties) {
        int configuredMax = Math.max(
                Math.max(aiProperties.getAsr().getTimeoutSeconds(), aiProperties.getSummary().getTimeoutSeconds()),
                Math.max(Math.max(aiProperties.getEmbedding().getTimeoutSeconds(),
                        aiProperties.getRerank().getTimeoutSeconds()), aiProperties.getChat().getTimeoutSeconds())
        );
        return Math.max(300, configuredMax);
    }
}
