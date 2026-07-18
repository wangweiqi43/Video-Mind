package com.videomind.agentclient;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Map;
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

    @Test
    void ingestContractContainsTranscriptButNotVideoUrl() throws Exception {
        AgentTaskClient.AgentIngestRequest request = new AgentTaskClient.AgentIngestRequest(
                1L,
                2L,
                3,
                "https://storage.example/transcript.txt",
                "zh-CN",
                Map.of("filename", "demo.mp4")
        );

        String json = new ObjectMapper().writeValueAsString(request);

        assertThat(json)
                .contains("\"transcriptUrl\"")
                .doesNotContain("videoUrl");
    }

    @Test
    void chatContractCarriesRequestScopedWebSearchPolicy() throws Exception {
        AgentChatClient.AgentChatRequest request = new AgentChatClient.AgentChatRequest(
                1L,
                "knowledge-base-1",
                2L,
                "请结合最新资料回答",
                "KNOWLEDGE_EXTENDED",
                "优先使用视频知识库",
                new AgentChatClient.AgentToolPolicy(true, true, false),
                null,
                java.util.List.of()
        );

        String json = new ObjectMapper().writeValueAsString(request);

        assertThat(json)
                .contains("\"toolPolicy\"")
                .contains("\"knowledgeBase\":true")
                .contains("\"webSearch\":true")
                .contains("\"deepResearch\":false");
    }

    private String hmac(String secret, String content) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(content.getBytes(StandardCharsets.UTF_8)));
    }
}
