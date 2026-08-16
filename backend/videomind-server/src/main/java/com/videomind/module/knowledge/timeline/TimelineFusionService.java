package com.videomind.module.knowledge.timeline;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** Builds a layered timeline whose visual spans and speech blocks have independent boundaries. */
@Slf4j
@Component
public class TimelineFusionService {
    static final String SCHEMA_VERSION = "timeline-layered-v1";
    static final int SPEECH_BLOCK_CHARACTER_LIMIT = 250;
    static final long SPEECH_BLOCK_SILENCE_GAP_MS = 5_000;
    static final double MIN_OCR_CONFIDENCE = 0.50;
    static final double OCR_SIMILARITY_THRESHOLD = 0.88;

    public Timeline fuse(List<AsrSegment> asrSegments, List<OcrObservation> ocrObservations,
                         long videoDurationMs, long visualTailFallbackMs) {
        if (visualTailFallbackMs <= 0) {
            throw new IllegalArgumentException("OCR_MAX_WINDOW_REQUIRED");
        }
        List<AsrSegment> speech = cleanSpeech(asrSegments);
        List<OcrObservation> visuals = cleanVisuals(ocrObservations);
        long durationMs = resolveDuration(videoDurationMs, speech, visuals, visualTailFallbackMs);
        List<VisualSpan> visualSpans = buildVisualSpans(visuals, durationMs);
        List<SpeechBlock> speechBlocks = buildSpeechBlocks(speech);
        return new Timeline(SCHEMA_VERSION, visualSpans, speechBlocks);
    }

    public String renderMarkdown(Timeline timeline, String title) {
        StringBuilder markdown = new StringBuilder("# ")
                .append(normalize(title).isBlank() ? "视频时间轴" : normalize(title))
                .append("\n\n");
        List<MarkdownEntry> entries = new ArrayList<>();
        for (VisualSpan span : timeline.visualSpans()) {
            entries.add(new MarkdownEntry(span.startMs(), 0, "画面区间", span.endMs(), "画面文字", span.text()));
        }
        for (SpeechBlock block : timeline.speechBlocks()) {
            entries.add(new MarkdownEntry(block.startMs(), 1, "语音区间", block.endMs(), "语音", block.text()));
        }
        entries.sort(Comparator.comparingLong(MarkdownEntry::startMs).thenComparingInt(MarkdownEntry::order));
        for (MarkdownEntry entry : entries) {
            markdown.append("## ").append(entry.heading()).append(" ")
                    .append(format(entry.startMs())).append(" - ").append(format(entry.endMs())).append("\n\n")
                    .append("- ").append(entry.label()).append("：").append(singleLine(entry.text()))
                    .append("\n\n");
        }
        return markdown.toString();
    }

    private static List<SpeechBlock> buildSpeechBlocks(List<AsrSegment> values) {
        if (values.isEmpty()) {
            return List.of();
        }
        List<SpeechBlock> result = new ArrayList<>();
        SpeechAccumulator current = null;
        for (AsrSegment segment : values) {
            if (current == null) {
                current = new SpeechAccumulator(segment);
                continue;
            }
            long silenceGap = segment.startMs() - current.lastEndMs;
            boolean characterLimitReached = current.projectedTextLength(segment)
                    > SPEECH_BLOCK_CHARACTER_LIMIT;
            if (silenceGap > SPEECH_BLOCK_SILENCE_GAP_MS || characterLimitReached) {
                result.add(current.toBlock());
                current = new SpeechAccumulator(segment);
            } else {
                current.append(segment);
            }
        }
        if (current != null) {
            result.add(current.toBlock());
        }
        return List.copyOf(result);
    }

    private static List<VisualSpan> buildVisualSpans(List<OcrObservation> values, long durationMs) {
        List<VisualSpan> result = new ArrayList<>();
        VisualAccumulator current = null;
        for (OcrObservation sample : values) {
            if (sample.startMs() >= durationMs) {
                break;
            }
            if (sample.text().isBlank() || sample.confidence() < MIN_OCR_CONFIDENCE) {
                continue;
            }
            if (current == null) {
                current = new VisualAccumulator(sample);
                continue;
            }
            if (similarity(current.anchorText, sample.text()) >= OCR_SIMILARITY_THRESHOLD) {
                current.sampleCount++;
                continue;
            }
            if (sample.startMs() > current.startMs) {
                result.add(current.toSpan(sample.startMs()));
            }
            current = new VisualAccumulator(sample);
        }
        if (current != null && durationMs > current.startMs) {
            result.add(current.toSpan(durationMs));
        }
        return List.copyOf(result);
    }

    private static List<AsrSegment> cleanSpeech(List<AsrSegment> values) {
        if (values == null) {
            return List.of();
        }
        List<IndexedAsr> ordered = new ArrayList<>();
        for (int index = 0; index < values.size(); index++) {
            AsrSegment value = values.get(index);
            if (value == null || !validRange(value.startMs(), value.endMs())) {
                continue;
            }
            String text = normalize(value.text());
            if (!text.isBlank()) {
                ordered.add(new IndexedAsr(index, new AsrSegment(value.startMs(), value.endMs(), text,
                        clamp(value.confidence()))));
            }
        }
        ordered.sort(Comparator.comparingLong((IndexedAsr value) -> value.segment().startMs())
                .thenComparingLong(value -> value.segment().endMs()).thenComparingInt(IndexedAsr::index));
        return ordered.stream().map(IndexedAsr::segment).toList();
    }

    private static List<OcrObservation> cleanVisuals(List<OcrObservation> values) {
        if (values == null) {
            return List.of();
        }
        List<IndexedOcr> ordered = new ArrayList<>();
        for (int index = 0; index < values.size(); index++) {
            OcrObservation value = values.get(index);
            if (value == null || value.startMs() < 0) {
                continue;
            }
            ordered.add(new IndexedOcr(index, new OcrObservation(value.startMs(), value.startMs(),
                    normalize(value.text()), clamp(value.confidence()))));
        }
        ordered.sort(Comparator.comparingLong((IndexedOcr value) -> value.sample().startMs())
                .thenComparingInt(IndexedOcr::index));
        Map<Long, OcrObservation> unique = new LinkedHashMap<>();
        for (IndexedOcr value : ordered) {
            OcrObservation existing = unique.get(value.sample().startMs());
            OcrObservation candidate = value.sample();
            if (existing == null || (!usable(existing) && usable(candidate))) {
                unique.put(candidate.startMs(), candidate);
            }
        }
        return List.copyOf(unique.values());
    }

    private static boolean usable(OcrObservation value) {
        return !value.text().isBlank() && value.confidence() >= MIN_OCR_CONFIDENCE;
    }

    private static long resolveDuration(long configuredDurationMs, List<AsrSegment> speech,
                                        List<OcrObservation> visuals, long visualTailFallbackMs) {
        if (configuredDurationMs > 0) {
            return configuredDurationMs;
        }
        long duration = 0;
        for (AsrSegment value : speech) {
            duration = Math.max(duration, value.endMs());
        }
        for (OcrObservation value : visuals) {
            duration = Math.max(duration, Math.addExact(value.startMs(), visualTailFallbackMs));
        }
        if (duration <= 0) {
            throw new IllegalArgumentException("VIDEO_DURATION_REQUIRED");
        }
        log.warn("Video duration missing; use analysis artifact boundary, durationMs={}", duration);
        return duration;
    }

    static double similarity(String left, String right) {
        String a = canonical(left);
        String b = canonical(right);
        if (a.equals(b)) {
            return 1;
        }
        if (a.isEmpty() || b.isEmpty()) {
            return 0;
        }
        int[] previous = new int[b.length() + 1];
        for (int column = 0; column <= b.length(); column++) {
            previous[column] = column;
        }
        for (int row = 1; row <= a.length(); row++) {
            int[] current = new int[b.length() + 1];
            current[0] = row;
            for (int column = 1; column <= b.length(); column++) {
                int replace = previous[column - 1] + (a.charAt(row - 1) == b.charAt(column - 1) ? 0 : 1);
                current[column] = Math.min(Math.min(previous[column] + 1, current[column - 1] + 1), replace);
            }
            previous = current;
        }
        return 1d - (double) previous[b.length()] / Math.max(a.length(), b.length());
    }

    static String format(long milliseconds) {
        long totalSeconds = milliseconds / 1000;
        long hours = totalSeconds / 3600;
        long minutes = totalSeconds % 3600 / 60;
        long seconds = totalSeconds % 60;
        long millis = milliseconds % 1000;
        return hours > 0 ? String.format(Locale.ROOT, "%02d:%02d:%02d.%03d", hours, minutes, seconds, millis)
                : String.format(Locale.ROOT, "%02d:%02d.%03d", minutes, seconds, millis);
    }

    private static String canonical(String value) {
        return normalize(value).toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
    }

    private static String normalize(String value) {
        return Objects.toString(value, "").strip().replaceAll("\\s+", " ");
    }

    private static String singleLine(String value) {
        return normalize(value).replace("|", "\\|");
    }

    private static String join(String left, String right) {
        if (left.endsWith("。") || left.endsWith("！") || left.endsWith("？")
                || left.endsWith(".") || left.endsWith("!") || left.endsWith("?")) {
            return left + " " + right;
        }
        return left + "，" + right;
    }

    private static double clamp(double value) {
        if (Double.isNaN(value)) {
            return 0;
        }
        return Math.max(0, Math.min(1, value));
    }

    private static boolean validRange(long start, long end) {
        return start >= 0 && end > start;
    }

    private static final class SpeechAccumulator {
        private final long startMs;
        private long endMs;
        private long lastEndMs;
        private String text;
        private int segmentCount;

        private SpeechAccumulator(AsrSegment segment) {
            startMs = segment.startMs();
            endMs = segment.endMs();
            lastEndMs = segment.endMs();
            text = segment.text();
            segmentCount = 1;
        }

        private void append(AsrSegment segment) {
            endMs = Math.max(endMs, segment.endMs());
            lastEndMs = Math.max(lastEndMs, segment.endMs());
            text = join(text, segment.text());
            segmentCount++;
        }

        private int projectedTextLength(AsrSegment segment) {
            return join(text, segment.text()).length();
        }

        private SpeechBlock toBlock() {
            return new SpeechBlock(startMs, endMs, text, segmentCount);
        }
    }

    private static final class VisualAccumulator {
        private final long startMs;
        private final String anchorText;
        private final double confidence;
        private int sampleCount = 1;

        private VisualAccumulator(OcrObservation sample) {
            startMs = sample.startMs();
            anchorText = sample.text();
            confidence = sample.confidence();
        }

        private VisualSpan toSpan(long endMs) {
            return new VisualSpan(startMs, endMs, anchorText, confidence, sampleCount);
        }
    }

    private record IndexedAsr(int index, AsrSegment segment) {
    }

    private record IndexedOcr(int index, OcrObservation sample) {
    }

    private record MarkdownEntry(long startMs, int order, String heading, long endMs, String label, String text) {
    }

    public record AsrSegment(long startMs, long endMs, String text, double confidence) {
    }

    public record OcrObservation(long startMs, long endMs, String text, double confidence) {
    }

    public record VisualSpan(long startMs, long endMs, String text, double confidence, int sampleCount) {
    }

    public record SpeechBlock(long startMs, long endMs, String text, int segmentCount) {
    }

    public record Timeline(String schemaVersion, List<VisualSpan> visualSpans, List<SpeechBlock> speechBlocks) {
        public Timeline {
            schemaVersion = Objects.requireNonNullElse(schemaVersion, SCHEMA_VERSION);
            visualSpans = visualSpans == null ? List.of() : List.copyOf(visualSpans);
            speechBlocks = speechBlocks == null ? List.of() : List.copyOf(speechBlocks);
        }

        public boolean isEmpty() {
            return visualSpans.isEmpty() && speechBlocks.isEmpty();
        }
    }
}
