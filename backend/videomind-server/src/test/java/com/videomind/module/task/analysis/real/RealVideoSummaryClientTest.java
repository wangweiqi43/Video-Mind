package com.videomind.module.task.analysis.real;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.videomind.config.AiProperties;
import com.videomind.module.knowledge.timeline.FusedVideoContent;
import com.videomind.module.knowledge.timeline.TimelineFusionService;
import com.videomind.module.knowledge.timeline.TimelineFusionService.AsrSegment;
import com.videomind.module.knowledge.timeline.TimelineFusionService.OcrObservation;
import com.videomind.module.video.entity.VideoFile;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import java.util.List;
import org.junit.jupiter.api.Test;

class RealVideoSummaryClientTest {
    @Test
    void sendsTheFusedTimelineIncludingVisualTextAsTheOnlySummarySource() {
        AiProperties properties = new AiProperties();
        AiProperties.ApiProvider provider = properties.getSummary();
        provider.setModel("summary-model");
        provider.setPromptVersion("summary-v6-layered-fusion");
        TimelineFusionService fusion = new TimelineFusionService();
        var timeline = fusion.fuse(List.of(new AsrSegment(1_000, 3_000, "解释消息队列", 1.0)),
                List.of(new OcrObservation(1_500, 1_500, "RocketMQ", 0.95)), 3_000, 30_000);
        FusedVideoContent content = new FusedVideoContent("课程.mp4", timeline,
                fusion.renderMarkdown(timeline, "课程.mp4 · 时间轴"), 1, 1, false);
        VideoFile video = new VideoFile();
        video.setOriginalFilename("课程.mp4");
        RealVideoSummaryClient client = new RealVideoSummaryClient(properties, mock(ChatModel.class),
                new ObjectMapper());

        ChatRequest request = client.buildRequest(content, video);
        List<dev.langchain4j.data.message.ChatMessage> messages = request.messages();
        String systemPrompt = ((SystemMessage) messages.get(0)).text();
        String userPrompt = ((UserMessage) messages.get(1)).singleText();

        assertThat(systemPrompt).contains("融合视频时间线", "语音区间", "画面区间",
                "不得把同一画面文字重复扩写", "禁止臆断");
        assertThat(userPrompt)
                .contains("融合视频时间线：", "语音区间 00:01.000 - 00:03.000",
                        "画面区间 00:01.500 - 00:03.000", "语音：解释消息队列", "画面文字：RocketMQ")
                .doesNotContain("转录文本：");
        assertThat(request.temperature()).isEqualTo(0.2);
    }
}
