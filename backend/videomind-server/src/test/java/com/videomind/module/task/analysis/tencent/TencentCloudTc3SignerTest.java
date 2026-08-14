package com.videomind.module.task.analysis.tencent;

import static org.assertj.core.api.Assertions.assertThat;

import com.videomind.config.TencentAsrProperties;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class TencentCloudTc3SignerTest {
    @Test
    void signsStableTc3HeadersWithoutExposingSecretKey() {
        TencentAsrProperties properties = new TencentAsrProperties();
        properties.setSecretId("AKIDEXAMPLE");
        properties.setSecretKey("SECRETEXAMPLE");
        properties.setRegion("ap-shanghai");
        TencentCloudTc3Signer signer = new TencentCloudTc3Signer(properties);

        var headers = signer.sign("DescribeTaskStatus", "{\"TaskId\":1}", Instant.ofEpochSecond(1_591_142_563L));

        assertThat(headers.host()).isEqualTo("asr.tencentcloudapi.com");
        assertThat(headers.timestamp()).isEqualTo("1591142563");
        assertThat(headers.authorization())
                .startsWith("TC3-HMAC-SHA256 Credential=AKIDEXAMPLE/2020-06-03/asr/tc3_request")
                .contains("SignedHeaders=content-type;host", "Signature=")
                .doesNotContain("SECRETEXAMPLE");
        assertThat(signer.sign("DescribeTaskStatus", "{\"TaskId\":2}",
                Instant.ofEpochSecond(1_591_142_563L)).authorization()).isNotEqualTo(headers.authorization());
    }
}
