package com.videomind.config;

import lombok.Data;
import lombok.EqualsAndHashCode;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "videomind.ai")
public class AiProperties {

    private ApiProvider asr = new ApiProvider();
    private ApiProvider summary = new ApiProvider();
    private EmbeddingProvider embedding = new EmbeddingProvider();
    private ApiProvider rerank = new ApiProvider();
    private ApiProvider chat = new ApiProvider();

    @Data
    public static class ApiProvider {

        /**
         * mock: use local placeholder implementation; real: call configured third-party API.
         */
        private String mode = "mock";
        private String endpoint;
        private String apiKey;
        private String model;
        private String promptVersion = "v1";
        private Integer timeoutSeconds = 60;
    }

    @Data
    @EqualsAndHashCode(callSuper = true)
    public static class EmbeddingProvider extends ApiProvider {

        private Integer dimension = 64;
    }
}
