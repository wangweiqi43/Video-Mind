package com.videomind.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.videomind.agentclient.AgentClientProperties;
import org.junit.jupiter.api.Test;

class MinioPresignConfigurationValidatorTest {

    @Test
    void requiresAbsolutePresignEndpointOnlyWhenIngestIsEnabled() {
        AgentClientProperties agent = new AgentClientProperties();
        MinioProperties minio = new MinioProperties();
        assertThatCode(() -> new MinioPresignConfigurationValidator(agent, minio).afterPropertiesSet())
                .doesNotThrowAnyException();

        agent.setEnabled(true);
        agent.setIngestEnabled(true);
        minio.setPresignEndpoint("localhost:9000");
        assertThatThrownBy(() -> new MinioPresignConfigurationValidator(agent, minio).afterPropertiesSet())
                .hasMessageContaining("MINIO_PRESIGN_ENDPOINT");

        minio.setPresignEndpoint("http://host.docker.internal:9000");
        assertThatCode(() -> new MinioPresignConfigurationValidator(agent, minio).afterPropertiesSet())
                .doesNotThrowAnyException();
    }
}
