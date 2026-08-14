package com.videomind.module.task.analysis.tencent;

import com.videomind.config.TencentAsrProperties;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

@Component
public class TencentCloudTc3Signer {
    private static final String ALGORITHM = "TC3-HMAC-SHA256";
    private static final String SERVICE = "asr";
    private static final String VERSION = "2019-06-14";
    private static final String CONTENT_TYPE = "application/json; charset=utf-8";
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd")
            .withZone(ZoneOffset.UTC);
    private final TencentAsrProperties properties;

    public TencentCloudTc3Signer(TencentAsrProperties properties) {
        this.properties = properties;
    }

    public SignedHeaders sign(String action, String payload, Instant instant) {
        String host = URI.create(properties.getEndpoint()).getHost();
        String canonicalHeaders = "content-type:" + CONTENT_TYPE + "\n" + "host:" + host + "\n";
        String signedHeaders = "content-type;host";
        String canonicalRequest = "POST\n/\n\n" + canonicalHeaders + "\n" + signedHeaders + "\n"
                + sha256Hex(payload.getBytes(StandardCharsets.UTF_8));
        String date = DATE.format(instant);
        String credentialScope = date + "/" + SERVICE + "/tc3_request";
        String stringToSign = ALGORITHM + "\n" + instant.getEpochSecond() + "\n" + credentialScope + "\n"
                + sha256Hex(canonicalRequest.getBytes(StandardCharsets.UTF_8));
        byte[] secretDate = hmac(("TC3" + properties.getSecretKey()).getBytes(StandardCharsets.UTF_8), date);
        byte[] secretService = hmac(secretDate, SERVICE);
        byte[] secretSigning = hmac(secretService, "tc3_request");
        String signature = HexFormat.of().formatHex(hmac(secretSigning, stringToSign));
        String authorization = ALGORITHM + " Credential=" + properties.getSecretId() + "/" + credentialScope
                + ", SignedHeaders=" + signedHeaders + ", Signature=" + signature;
        return new SignedHeaders(host, CONTENT_TYPE, action, VERSION, properties.getRegion(),
                Long.toString(instant.getEpochSecond()), authorization);
    }

    private static String sha256Hex(byte[] input) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    private static byte[] hmac(byte[] key, String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception exception) {
            throw new IllegalStateException("HmacSHA256 不可用", exception);
        }
    }

    public record SignedHeaders(String host, String contentType, String action, String version,
                                String region, String timestamp, String authorization) {
    }
}
