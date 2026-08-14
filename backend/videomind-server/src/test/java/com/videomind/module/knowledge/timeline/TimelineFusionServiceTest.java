package com.videomind.module.knowledge.timeline;

import static org.assertj.core.api.Assertions.assertThat;

import com.videomind.module.knowledge.timeline.TimelineFusionService.AsrSegment;
import com.videomind.module.knowledge.timeline.TimelineFusionService.OcrObservation;
import java.util.List;
import org.junit.jupiter.api.Test;

class TimelineFusionServiceTest {
    private final TimelineFusionService service = new TimelineFusionService();

    @Test
    void alignsDebouncedKeyframeTextWithTimestampedSpeech() {
        var timeline = service.fuse(List.of(
                        new AsrSegment(0, 4_000, "今天介绍事务消息", 0.91),
                        new AsrSegment(4_300, 8_000, "先发送 half message", 0.88)),
                List.of(
                        new OcrObservation(1_000, 1_200, "RocketMQ 事务消息", 0.80),
                        new OcrObservation(2_500, 2_700, "RocketMQ事务消息", 0.92),
                        new OcrObservation(5_000, 5_200, "RocketMQ 事务消恳", 0.70)));

        assertThat(timeline.events()).hasSize(1);
        assertThat(timeline.events().get(0).speechText()).contains("今天介绍事务消息", "half message");
        assertThat(timeline.events().get(0).visualTexts()).containsExactly("RocketMQ事务消息");
        assertThat(timeline.events().get(0).startMs()).isZero();
        assertThat(timeline.events().get(0).endMs()).isEqualTo(8_000);
    }

    @Test
    void preservesVisualOnlyEventsAndDropsInvalidLowConfidenceNoise() {
        var timeline = service.fuse(List.of(), List.of(
                new OcrObservation(10_000, 11_000, "架构总览", 0.95),
                new OcrObservation(12_000, 11_000, "非法时间", 0.99),
                new OcrObservation(20_000, 21_000, "噪声", 0.20)));

        assertThat(timeline.events()).hasSize(1);
        assertThat(timeline.events().get(0).speechText()).isEmpty();
        assertThat(timeline.events().get(0).visualTexts()).containsExactly("架构总览");
    }

    @Test
    void doesNotJoinSpeechIntoUnboundedEvents() {
        var timeline = service.fuse(List.of(
                new AsrSegment(0, 9_000, "第一段", 0.9),
                new AsrSegment(9_200, 17_000, "第二段", 0.9)), List.of());
        assertThat(timeline.events()).hasSize(2);
    }

    @Test
    void rendersDeterministicTimelineMarkdownWithHourAwareTimestamps() {
        var timeline = service.fuse(List.of(
                new AsrSegment(3_661_001, 3_663_020, "总结", 0.9)),
                List.of(new OcrObservation(3_661_200, 3_662_000, "Thank you", 0.9)));

        String markdown = service.renderMarkdown(timeline, "演示视频时间轴");

        assertThat(markdown).startsWith("# 演示视频时间轴\n\n");
        assertThat(markdown).contains("## 01:01:01.001 - 01:01:03.020", "- 语音：总结", "- 画面文字：Thank you");
    }

    @Test
    void fuzzyOcrSimilarityToleratesOneRecognitionError() {
        assertThat(TimelineFusionService.similarity("部署 RocketMQ", "部署 RocketMO")).isGreaterThan(0.88);
    }
}
