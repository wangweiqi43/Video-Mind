package com.videomind.agentclient;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class AgentSigningTest {

    @Test
    void signsCanonicalRequestWithHmacSha256() throws Exception {
        AgentClientProperties properties = new AgentClientProperties();
        properties.setSigningSecret("request-secret");
        AgentApiClient client = new AgentApiClient(HttpClient.newHttpClient(), new ObjectMapper(), properties);

        String canonical = "1700000000\nPOST\n/v1/ingest\n{\"videoId\":1}";
        assertThat(client.sign("POST", "/v1/ingest", "1700000000", "{\"videoId\":1}"))
                .isEqualTo(hmac("request-secret", canonical));
    }

    private String hmac(String secret, String content) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(content.getBytes(StandardCharsets.UTF_8)));
    }
}
