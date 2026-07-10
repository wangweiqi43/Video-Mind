package com.videomind.agentclient;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@RequiredArgsConstructor
public class AgentWebhookVerifier {

    private static final long MAX_CLOCK_SKEW_SECONDS = 300;
    private final AgentClientProperties properties;

    public boolean verify(String timestamp, String body, String signature) {
        if (!StringUtils.hasText(properties.getWebhookSecret())
                || !StringUtils.hasText(timestamp)
                || !StringUtils.hasText(signature)) {
            return false;
        }
        try {
            long requestTime = Long.parseLong(timestamp);
            if (Math.abs(Instant.now().getEpochSecond() - requestTime) > MAX_CLOCK_SKEW_SECONDS) {
                return false;
            }
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(properties.getWebhookSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String expected = HexFormat.of().formatHex(mac.doFinal((timestamp + "\n" + body)
                    .getBytes(StandardCharsets.UTF_8)));
            return MessageDigest.isEqual(
                    expected.getBytes(StandardCharsets.US_ASCII),
                    signature.getBytes(StandardCharsets.US_ASCII)
            );
        } catch (Exception ignored) {
            return false;
        }
    }
}
