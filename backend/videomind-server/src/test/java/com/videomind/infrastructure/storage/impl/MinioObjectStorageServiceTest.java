package com.videomind.infrastructure.storage.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.videomind.config.MinioProperties;
import io.minio.MinioClient;
import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class MinioObjectStorageServiceTest {

    @Test
    void presignedUrlUsesDockerReachableHostWithoutNetworkProbe() throws Exception {
        MinioProperties properties = new MinioProperties();
        properties.setEndpoint("http://localhost:9000");
        properties.setPresignEndpoint("http://host.docker.internal:9000");
        properties.setAccessKey("minioadmin");
        properties.setSecretKey("minioadmin");
        properties.setBucket("videomind-videos");
        MinioObjectStorageService storage = new MinioObjectStorageService(mock(MinioClient.class), properties);

        URI uri = URI.create(storage.presignGetUrl("videomind-videos", "agent-input/test.txt", Duration.ofMinutes(5)));

        assertThat(uri.getHost()).isEqualTo("host.docker.internal");
        assertThat(uri.getPort()).isEqualTo(9000);
        assertThat(uri.getQuery()).contains("X-Amz-Signature");
    }
}
