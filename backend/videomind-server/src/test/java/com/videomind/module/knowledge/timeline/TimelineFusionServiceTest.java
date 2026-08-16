package com.videomind.module.knowledge.timeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.videomind.module.knowledge.timeline.TimelineFusionService.AsrSegment;
import com.videomind.module.knowledge.timeline.TimelineFusionService.OcrObservation;
import com.videomind.module.knowledge.timeline.TimelineFusionService.SpeechBlock;
import com.videomind.module.knowledge.timeline.TimelineFusionService.Timeline;
import com.videomind.module.knowledge.timeline.TimelineFusionService.VisualSpan;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class TimelineFusionServiceTest {
    private static final long VISUAL_TAIL_FALLBACK_MS = 30_000;
    private final TimelineFusionService service = new TimelineFusionService();

    @Test
    void buildsIndependentVisualSpansAndSpeechBlocks() {
        Timeline timeline = service.fuse(List.of(
                        new AsrSegment(1_000, 4_000, "第一句", 0.91),
                        new AsrSegment(4_300, 8_000, "第二句", 0.88)),
                List.of(new OcrObservation(500, 500, "架构总览", 0.92)),
                10_000, VISUAL_TAIL_FALLBACK_MS);

        assertThat(timeline.schemaVersion()).isEqualTo("timeline-layered-v1");
        assertThat(timeline.visualSpans()).containsExactly(
                new VisualSpan(500, 10_000, "架构总览", 0.92, 1));
        assertThat(timeline.speechBlocks()).containsExactly(
                new SpeechBlock(1_000, 8_000, "第一句，第二句", 2));
    }

    @Test
    void taskTwentyTwoStaticWordSamplesBecomeOneLongVisualSpan() {
        long[] timestamps = {320, 30_348, 60_377, 90_407, 120_437, 150_467, 180_498, 210_527,
                240_557, 270_586, 300_617, 330_647, 360_677, 390_707, 420_738, 450_768,
                480_797, 510_827, 540_858, 570_887, 600_917, 630_947, 660_978};
        List<OcrObservation> samples = new ArrayList<>();
        samples.add(new OcrObservation(0, 0, "", 1));
        for (long timestamp : timestamps) {
            samples.add(new OcrObservation(timestamp, timestamp, "Microsoft Word 跨文化交际复习范围", 0.9));
        }
        samples.add(new OcrObservation(672_790, 672_790, "毛老师的共享屏幕", 0.99));
        samples.add(new OcrObservation(673_550, 673_550, "毛老师", 0.99));
        samples.add(new OcrObservation(681_472, 681_472, "腾讯会议 公开", 0.99));

        Timeline timeline = service.fuse(List.of(), samples, 683_000, VISUAL_TAIL_FALLBACK_MS);

        assertThat(timeline.visualSpans()).containsExactly(
                new VisualSpan(320, 672_790, "Microsoft Word 跨文化交际复习范围", 0.9, 23),
                new VisualSpan(672_790, 673_550, "毛老师的共享屏幕", 0.99, 1),
                new VisualSpan(673_550, 681_472, "毛老师", 0.99, 1),
                new VisualSpan(681_472, 683_000, "腾讯会议 公开", 0.99, 1));
    }

    @Test
    void comparesAgainstRetainedAnchorSoCumulativeChangeStartsANewSpan() {
        Timeline timeline = service.fuse(List.of(), List.of(
                        new OcrObservation(0, 0, "ABCDEFGHIJ", 0.9),
                        new OcrObservation(10_000, 10_000, "ABCDEFGHIX", 0.9),
                        new OcrObservation(20_000, 20_000, "ABCDEFXXYY", 0.9)),
                30_000, VISUAL_TAIL_FALLBACK_MS);

        assertThat(timeline.visualSpans()).containsExactly(
                new VisualSpan(0, 20_000, "ABCDEFGHIJ", 0.9, 2),
                new VisualSpan(20_000, 30_000, "ABCDEFXXYY", 0.9, 1));
    }

    @Test
    void blankLowConfidenceAndDuplicateSamplesDoNotCloseAVisualSpan() {
        Timeline timeline = service.fuse(List.of(), List.of(
                        new OcrObservation(20_000, 20_000, "", 0.9),
                        new OcrObservation(10_000, 10_000, "", 0.1),
                        new OcrObservation(10_000, 10_000, "有效标题", 0.9),
                        new OcrObservation(30_000, 30_000, "", 0.9),
                        new OcrObservation(50_000, 50_000, "超出视频", 0.9)),
                40_000, VISUAL_TAIL_FALLBACK_MS);

        assertThat(timeline.visualSpans()).containsExactly(
                new VisualSpan(10_000, 40_000, "有效标题", 0.9, 1));
    }

    @Test
    void groupsWholeSpeechSegmentsByCharacterSoftLimitWithoutDuplication() {
        String first = "甲".repeat(120);
        String second = "乙".repeat(120);
        String third = "丙".repeat(20);
        Timeline timeline = service.fuse(List.of(
                        new AsrSegment(0, 10_000, first, 0.9),
                        new AsrSegment(10_000, 20_000, second, 0.9),
                        new AsrSegment(20_000, 30_000, third, 0.9)),
                List.of(), 30_000, VISUAL_TAIL_FALLBACK_MS);

        assertThat(timeline.speechBlocks()).containsExactly(
                new SpeechBlock(0, 20_000, first + "，" + second, 2),
                new SpeechBlock(20_000, 30_000, third, 1));
        assertThat(timeline.speechBlocks()).extracting(SpeechBlock::segmentCount).containsExactly(2, 1);
    }

    @Test
    void includesTheSentenceSeparatorInTheCharacterSoftLimit() {
        String first = "甲".repeat(125);
        String second = "乙".repeat(125);
        Timeline timeline = service.fuse(List.of(
                        new AsrSegment(0, 10_000, first, 0.9),
                        new AsrSegment(10_000, 20_000, second, 0.9)),
                List.of(), 20_000, VISUAL_TAIL_FALLBACK_MS);

        assertThat(timeline.speechBlocks()).containsExactly(
                new SpeechBlock(0, 10_000, first, 1),
                new SpeechBlock(10_000, 20_000, second, 1));
    }

    @Test
    void keepsAThirtyFourSecondOversizedSentenceWhole() {
        String sentence = "完整长句".repeat(70);
        Timeline timeline = service.fuse(List.of(
                        new AsrSegment(631_380, 665_560, sentence, 0.9),
                        new AsrSegment(666_000, 670_000, "下一句", 0.9)),
                List.of(), 683_000, VISUAL_TAIL_FALLBACK_MS);

        assertThat(timeline.speechBlocks()).containsExactly(
                new SpeechBlock(631_380, 665_560, sentence, 1),
                new SpeechBlock(666_000, 670_000, "下一句", 1));
    }

    @Test
    void startsANewSpeechBlockAfterFiveSecondsOfSilence() {
        Timeline timeline = service.fuse(List.of(
                        new AsrSegment(0, 1_000, "前句", 0.9),
                        new AsrSegment(6_001, 7_000, "后句", 0.9)),
                List.of(), 10_000, VISUAL_TAIL_FALLBACK_MS);

        assertThat(timeline.speechBlocks()).containsExactly(
                new SpeechBlock(0, 1_000, "前句", 1),
                new SpeechBlock(6_001, 7_000, "后句", 1));
    }

    @Test
    void overlappingSpeechUsesTheFurthestPreviousEndAndKeepsEverySegmentOnce() {
        Timeline timeline = service.fuse(List.of(
                        new AsrSegment(0, 10_000, "主句", 0.9),
                        new AsrSegment(5_000, 7_000, "重叠补充", 0.9),
                        new AsrSegment(13_000, 14_000, "后续句子", 0.9)),
                List.of(), 20_000, VISUAL_TAIL_FALLBACK_MS);

        assertThat(timeline.speechBlocks()).containsExactly(
                new SpeechBlock(0, 14_000, "主句，重叠补充，后续句子", 3));
    }

    @Test
    void supportsSpeechOnlyAndVisualOnlyLayeredTimelines() {
        Timeline speechOnly = service.fuse(List.of(new AsrSegment(0, 1_000, "只有语音", 0.9)),
                List.of(), 2_000, VISUAL_TAIL_FALLBACK_MS);
        Timeline visualOnly = service.fuse(List.of(),
                List.of(new OcrObservation(500, 500, "只有画面", 0.9)),
                2_000, VISUAL_TAIL_FALLBACK_MS);

        assertThat(speechOnly.visualSpans()).isEmpty();
        assertThat(speechOnly.speechBlocks()).hasSize(1);
        assertThat(visualOnly.speechBlocks()).isEmpty();
        assertThat(visualOnly.visualSpans()).hasSize(1);
    }

    @Test
    void rejectsTimelineWhenDurationCannotBeResolved() {
        assertThatThrownBy(() -> service.fuse(List.of(), List.of(), 0, VISUAL_TAIL_FALLBACK_MS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("VIDEO_DURATION_REQUIRED");
    }

    @Test
    void infersVisualTailWhenVideoDurationIsMissing() {
        Timeline timeline = service.fuse(List.of(),
                List.of(new OcrObservation(1_000, 1_000, "尾页", 0.9)),
                0, VISUAL_TAIL_FALLBACK_MS);

        assertThat(timeline.visualSpans()).containsExactly(
                new VisualSpan(1_000, 31_000, "尾页", 0.9, 1));
    }

    @Test
    void rendersLayeredMarkdownInChronologicalOrder() {
        Timeline timeline = new Timeline("timeline-layered-v1",
                List.of(new VisualSpan(1_500, 5_000, "RocketMQ", 0.9, 3)),
                List.of(new SpeechBlock(1_000, 3_000, "事务消息", 2)));

        String markdown = service.renderMarkdown(timeline, "课程视频时间轴");

        assertThat(markdown).startsWith("# 课程视频时间轴\n\n")
                .contains("## 语音区间 00:01.000 - 00:03.000", "- 语音：事务消息",
                        "## 画面区间 00:01.500 - 00:05.000", "- 画面文字：RocketMQ");
        assertThat(markdown.indexOf("语音区间")).isLessThan(markdown.indexOf("画面区间"));
    }

    @Test
    void fuzzyOcrSimilarityToleratesOneRecognitionError() {
        assertThat(TimelineFusionService.similarity("部署 RocketMQ", "部署 RocketMO")).isGreaterThan(0.88);
    }
}
