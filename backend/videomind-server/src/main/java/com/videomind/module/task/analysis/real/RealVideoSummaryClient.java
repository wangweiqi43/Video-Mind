package com.videomind.module.task.analysis.real;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.videomind.common.exception.BizException;
import com.videomind.config.AiProperties;
import com.videomind.infrastructure.ai.AiApiSupport;
import com.videomind.module.task.analysis.VideoSummaryClient;
import com.videomind.module.task.analysis.dto.AsrResult;
import com.videomind.module.task.analysis.dto.SummaryResult;
import com.videomind.module.task.entity.TaskRecord;
import com.videomind.module.video.entity.VideoFile;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "videomind.ai.summary", name = "mode", havingValue = "real")
public class RealVideoSummaryClient implements VideoSummaryClient {

    private final AiProperties aiProperties;
    private final RestClient aiRestClient;
    private final ObjectMapper objectMapper;

    @Override
    public SummaryResult summarize(AsrResult asrResult, VideoFile videoFile, TaskRecord taskRecord) {
        AiProperties.ApiProvider summary = aiProperties.getSummary();
        AiApiSupport.requireConfigured("摘要大模型", summary);

        JsonNode response = aiRestClient.post()
                .uri(summary.getEndpoint())
                .headers(headers -> AiApiSupport.setBearerAuth(headers, summary.getApiKey()))
                .contentType(MediaType.APPLICATION_JSON)
                .body(buildRequest(asrResult, videoFile, summary))
                .retrieve()
                .body(JsonNode.class);

        String content = parseContent(response);
        return SummaryResult.builder()
                .summaryText(content)
                .summaryJson(toSummaryJson(content, summary.getModel(), response))
                .modelName(modelSignature(summary))
                .build();
    }

    private Map<String, Object> buildRequest(AsrResult asrResult, VideoFile videoFile, AiProperties.ApiProvider summary) {
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of(
                "role", "system",
                "content", """
                        你是 VideoMind 的视频理解助手。请只基于用户提供的视频转录文本生成结构化摘要。

                        输出必须严格遵守以下 Markdown 格式：

                        ### 简洁摘要
                        用 1 个自然段概括视频核心内容，控制在 120 到 180 字，不要分点。

                        ### 这里写第一个要点的高度概括标题
                        用 1 到 2 个自然段解释该要点，内容要具体，不要再使用列表符号。

                        ### 这里写第二个要点的高度概括标题
                        用 1 到 2 个自然段解释该要点，内容要具体，不要再使用列表符号。

                        ### 这里写第三个要点的高度概括标题
                        用 1 到 2 个自然段解释该要点，内容要具体，不要再使用列表符号。

                        要求：
                        1. 除“### 简洁摘要”外，后续每个三级标题都必须直接写该要点的总结标题。
                        2. 标题中禁止出现“要点”“要点 1”“要点 2”“要点：”等字样。
                        3. 每个标题必须是对该段内容的总结，不能只写“核心特色”“实际场景”这种泛标题。
                        4. 根据视频内容输出 3 到 6 个要点标题。
                        5. 不要输出“可行动结论”“总结”“其他”等额外章节。
                        6. 不要输出项目符号列表，不要输出编号列表，只使用上述三级标题和段落。
                        """
        ));
        messages.add(Map.of(
                "role", "user",
                "content", """
                        视频文件：%s

                        转录文本：
                        %s
                        """.formatted(videoFile.getOriginalFilename(), asrResult.getText())
        ));

        Map<String, Object> request = new LinkedHashMap<>();
        if (StringUtils.hasText(summary.getModel())) {
            request.put("model", summary.getModel());
        }
        request.put("messages", messages);
        request.put("temperature", 0.2);
        return request;
    }

    private String parseContent(JsonNode response) {
        String content = AiApiSupport.firstText(
                response,
                "choices[0].message.content",
                "data.choices[0].message.content",
                "text",
                "summary",
                "data.text"
        );
        if (!StringUtils.hasText(content)) {
            throw new BizException(500, "摘要 API 响应中没有找到文本内容，请调整 RealVideoSummaryClient.parseContent 字段映射。");
        }
        return content;
    }

    private String toSummaryJson(String summaryText, String model, JsonNode rawResponse) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("title", "VideoMind Summary");
        json.put("summary", summaryText);
        json.put("model", StringUtils.hasText(model) ? model : "real-summary");
        json.put("promptVersion", aiProperties.getSummary().getPromptVersion());
        json.put("rawResponse", rawResponse);
        try {
            return objectMapper.writeValueAsString(json);
        } catch (JsonProcessingException e) {
            return "{\"title\":\"VideoMind Summary\"}";
        }
    }

    private String modelSignature(AiProperties.ApiProvider summary) {
        String model = StringUtils.hasText(summary.getModel()) ? summary.getModel() : "real-summary";
        String promptVersion = StringUtils.hasText(summary.getPromptVersion()) ? summary.getPromptVersion() : "v1";
        return model + "@" + promptVersion;
    }
}
