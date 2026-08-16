package com.videomind.module.task.analysis.real;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.videomind.common.exception.BizException;
import com.videomind.config.AiProperties;
import com.videomind.config.LangChain4jModelConfig;
import com.videomind.module.knowledge.timeline.FusedVideoContent;
import com.videomind.module.task.analysis.VideoSummaryClient;
import com.videomind.module.task.analysis.dto.SummaryResult;
import com.videomind.module.task.entity.TaskRecord;
import com.videomind.module.video.entity.VideoFile;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@ConditionalOnProperty(prefix = "videomind.ai.summary", name = "mode", havingValue = "real")
public class RealVideoSummaryClient implements VideoSummaryClient {

    static final String SYSTEM_PROMPT = """
            你是 VideoMind 的视频理解助手。请只基于用户提供的融合视频时间线生成结构化摘要。
            时间线由相互独立的“语音区间”和“画面区间”组成。“语音”来自 ASR，
            “画面文字”来自视频帧 OCR。画面文字在其画面区间内持续有效，但每个相似画面只提供一次，
            不得把同一画面文字重复扩写到该区间内的每个语音段。以语音为主要叙事依据，使用画面文字
            补充标题、专有名词、代码和演示内容；两者冲突时保留不确定性，禁止臆断。

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
            7. 不要在摘要中声称看到了时间线未提供的画面、人物、动作或文档内容。
            """;

    private final AiProperties aiProperties;
    private final ChatModel summaryModel;
    private final ObjectMapper objectMapper;

    public RealVideoSummaryClient(
            AiProperties aiProperties,
            @Qualifier(LangChain4jModelConfig.SUMMARY_MODEL) ChatModel summaryModel,
            ObjectMapper objectMapper
    ) {
        this.aiProperties = aiProperties;
        this.summaryModel = summaryModel;
        this.objectMapper = objectMapper;
    }

    @Override
    public SummaryResult summarize(FusedVideoContent content, VideoFile videoFile, TaskRecord taskRecord) {
        AiProperties.ApiProvider summary = aiProperties.getSummary();
        ChatRequest request = buildRequest(content, videoFile);
        ChatResponse response;
        try {
            response = summaryModel.chat(request);
        } catch (RuntimeException ex) {
            throw new BizException(500, "摘要模型调用失败：" + safeMessage(ex));
        }
        String summaryText = response.aiMessage().text();
        if (!StringUtils.hasText(summaryText)) {
            throw new BizException(500, "摘要模型响应中没有文本内容。");
        }
        return SummaryResult.builder()
                .summaryText(summaryText)
                .summaryJson(toSummaryJson(summaryText, summary.getModel(), response))
                .modelName(modelSignature(summary))
                .build();
    }

    ChatRequest buildRequest(FusedVideoContent content, VideoFile videoFile) {
        String userPrompt = """
                视频文件：%s

                融合视频时间线：
                %s
                """.formatted(videoFile.getOriginalFilename(), content.markdown());
        return ChatRequest.builder()
                .messages(SystemMessage.from(SYSTEM_PROMPT), UserMessage.from(userPrompt))
                .temperature(0.2)
                .build();
    }

    private String toSummaryJson(String summaryText, String model, ChatResponse response) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("title", "VideoMind Summary");
        json.put("summary", summaryText);
        json.put("model", StringUtils.hasText(model) ? model : "real-summary");
        json.put("promptVersion", aiProperties.getSummary().getPromptVersion());
        json.put("rawResponse", responseMetadata(response));
        try {
            return objectMapper.writeValueAsString(json);
        } catch (JsonProcessingException e) {
            return "{\"title\":\"VideoMind Summary\"}";
        }
    }

    private Map<String, Object> responseMetadata(ChatResponse response) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (response.metadata() == null) {
            return metadata;
        }
        putIfPresent(metadata, "id", response.metadata().id());
        putIfPresent(metadata, "modelName", response.metadata().modelName());
        putIfPresent(metadata, "finishReason", response.metadata().finishReason());
        TokenUsage usage = response.metadata().tokenUsage();
        if (usage != null) {
            Map<String, Object> tokens = new LinkedHashMap<>();
            putIfPresent(tokens, "input", usage.inputTokenCount());
            putIfPresent(tokens, "output", usage.outputTokenCount());
            putIfPresent(tokens, "total", usage.totalTokenCount());
            metadata.put("tokenUsage", tokens);
        }
        return metadata;
    }

    private static void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value != null) {
            target.put(key, value);
        }
    }

    private static String safeMessage(Throwable error) {
        return StringUtils.hasText(error.getMessage()) ? error.getMessage() : error.getClass().getSimpleName();
    }

    private String modelSignature(AiProperties.ApiProvider summary) {
        String model = StringUtils.hasText(summary.getModel()) ? summary.getModel() : "real-summary";
        String promptVersion = StringUtils.hasText(summary.getPromptVersion()) ? summary.getPromptVersion() : "v1";
        return model + "@" + promptVersion;
    }
}
