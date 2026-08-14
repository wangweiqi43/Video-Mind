package com.videomind.module.task.analysis.tencent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.videomind.common.exception.BizException;
import com.videomind.module.task.analysis.dto.AsrSegmentResult;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class TencentAsrResponseParser {
    private final ObjectMapper objectMapper;

    public TencentAsrResponseParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public TencentAsrTaskResult parse(String json) {
        try {
            return parse(objectMapper.readTree(json));
        } catch (IOException exception) {
            throw new BizException(502, "腾讯云 ASR 返回了无法解析的 JSON");
        }
    }

    public TencentAsrTaskResult parse(JsonNode root) {
        JsonNode response = root.path("Response");
        JsonNode apiError = response.path("Error");
        if (!apiError.isMissingNode() && !apiError.isNull()) {
            String code = apiError.path("Code").asText("UnknownError");
            String message = apiError.path("Message").asText("未知错误");
            throw new BizException(502, "腾讯云 ASR 调用失败 [" + code + "]：" + message);
        }

        JsonNode data = response.path("Data");
        if (data.isMissingNode() || data.isNull()) {
            throw new BizException(502, "腾讯云 ASR 响应缺少 Response.Data");
        }

        List<AsrSegmentResult> segments = parseSegments(data.path("ResultDetail"));
        String text = data.path("Result").asText("").trim();
        if (!StringUtils.hasText(text)) {
            text = segments.stream().map(AsrSegmentResult::text)
                    .filter(StringUtils::hasText)
                    .reduce((left, right) -> left + "\n" + right)
                    .orElse("");
        }
        return new TencentAsrTaskResult(
                data.path("TaskId").asLong(),
                status(data.path("Status").asInt(-1)),
                text,
                List.copyOf(segments),
                data.path("ErrorMsg").asText(""),
                response.path("RequestId").asText("")
        );
    }

    private List<AsrSegmentResult> parseSegments(JsonNode details) {
        if (!details.isArray()) {
            return List.of();
        }
        List<AsrSegmentResult> segments = new ArrayList<>();
        for (JsonNode detail : details) {
            long startMs = detail.path("StartMs").asLong(-1);
            long endMs = detail.path("EndMs").asLong(-1);
            String written = detail.path("WrittenText").asText("").trim();
            String text = StringUtils.hasText(written)
                    ? written
                    : detail.path("FinalSentence").asText("").trim();
            if (startMs >= 0 && endMs >= startMs && StringUtils.hasText(text)) {
                Integer speakerId = detail.hasNonNull("SpeakerId") ? detail.path("SpeakerId").asInt() : null;
                segments.add(new AsrSegmentResult(startMs, endMs, text, speakerId));
            }
        }
        return segments;
    }

    private TencentAsrTaskResult.Status status(int value) {
        return switch (value) {
            case 0 -> TencentAsrTaskResult.Status.WAITING;
            case 1 -> TencentAsrTaskResult.Status.RUNNING;
            case 2 -> TencentAsrTaskResult.Status.SUCCEEDED;
            case 3 -> TencentAsrTaskResult.Status.FAILED;
            default -> throw new BizException(502, "腾讯云 ASR 返回未知任务状态：" + value);
        };
    }
}
