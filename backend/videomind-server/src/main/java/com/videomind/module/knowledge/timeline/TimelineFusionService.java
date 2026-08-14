package com.videomind.module.knowledge.timeline;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class TimelineFusionService {
    static final long ASR_JOIN_GAP_MS = 800;
    static final long ASR_MAX_EVENT_MS = 15_000;
    static final long OCR_DEBOUNCE_GAP_MS = 4_000;
    static final long OCR_ATTACH_TOLERANCE_MS = 1_000;
    static final double MIN_OCR_CONFIDENCE = 0.50;

    public Timeline fuse(List<AsrSegment> asrSegments, List<OcrObservation> ocrObservations) {
        List<AsrSegment> speech = mergeSpeech(cleanSpeech(asrSegments));
        List<OcrObservation> visuals = debounceOcr(cleanOcr(ocrObservations));
        List<TimelineEvent> events = new ArrayList<>();
        boolean[] attached = new boolean[visuals.size()];
        for (AsrSegment segment : speech) {
            Set<String> visualText = new LinkedHashSet<>();
            long start = segment.startMs();
            long end = segment.endMs();
            for (int index = 0; index < visuals.size(); index++) {
                OcrObservation visual = visuals.get(index);
                if (nearOrOverlaps(segment.startMs(), segment.endMs(), visual.startMs(), visual.endMs())) {
                    visualText.add(visual.text());
                    attached[index] = true;
                    start = Math.min(start, visual.startMs());
                    end = Math.max(end, visual.endMs());
                }
            }
            events.add(new TimelineEvent(start, end, segment.text(), List.copyOf(visualText)));
        }
        for (int index = 0; index < visuals.size(); index++) {
            if (!attached[index]) {
                OcrObservation visual = visuals.get(index);
                events.add(new TimelineEvent(visual.startMs(), visual.endMs(), "", List.of(visual.text())));
            }
        }
        events.sort(Comparator.comparingLong(TimelineEvent::startMs).thenComparingLong(TimelineEvent::endMs));
        return new Timeline(List.copyOf(events));
    }

    public String renderMarkdown(Timeline timeline, String title) {
        StringBuilder markdown = new StringBuilder("# ")
                .append(normalize(title).isBlank() ? "视频时间轴" : normalize(title))
                .append("\n\n");
        for (TimelineEvent event : timeline.events()) {
            markdown.append("## ").append(format(event.startMs())).append(" - ")
                    .append(format(event.endMs())).append("\n\n");
            if (!event.speechText().isBlank()) {
                markdown.append("- 语音：").append(singleLine(event.speechText())).append("\n");
            }
            if (!event.visualTexts().isEmpty()) {
                markdown.append("- 画面文字：")
                        .append(event.visualTexts().stream().map(TimelineFusionService::singleLine)
                                .collect(Collectors.joining("；")))
                        .append("\n");
            }
            markdown.append("\n");
        }
        return markdown.toString();
    }

    private static List<AsrSegment> cleanSpeech(List<AsrSegment> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream().filter(Objects::nonNull)
                .filter(value -> validRange(value.startMs(), value.endMs()))
                .map(value -> new AsrSegment(value.startMs(), value.endMs(), normalize(value.text()),
                        clamp(value.confidence())))
                .filter(value -> !value.text().isBlank())
                .sorted(Comparator.comparingLong(AsrSegment::startMs).thenComparingLong(AsrSegment::endMs))
                .toList();
    }

    private static List<OcrObservation> cleanOcr(List<OcrObservation> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream().filter(Objects::nonNull)
                .filter(value -> validRange(value.startMs(), value.endMs()))
                .map(value -> new OcrObservation(value.startMs(), value.endMs(), normalize(value.text()),
                        clamp(value.confidence())))
                .filter(value -> !value.text().isBlank() && value.confidence() >= MIN_OCR_CONFIDENCE)
                .sorted(Comparator.comparingLong(OcrObservation::startMs)
                        .thenComparingLong(OcrObservation::endMs))
                .toList();
    }

    private static List<AsrSegment> mergeSpeech(List<AsrSegment> values) {
        List<AsrSegment> result = new ArrayList<>();
        for (AsrSegment next : values) {
            if (result.isEmpty()) {
                result.add(next);
                continue;
            }
            AsrSegment previous = result.get(result.size() - 1);
            boolean close = next.startMs() - previous.endMs() <= ASR_JOIN_GAP_MS;
            boolean bounded = Math.max(previous.endMs(), next.endMs()) - previous.startMs() <= ASR_MAX_EVENT_MS;
            if (close && bounded) {
                result.set(result.size() - 1, new AsrSegment(previous.startMs(),
                        Math.max(previous.endMs(), next.endMs()), join(previous.text(), next.text()),
                        Math.max(previous.confidence(), next.confidence())));
            } else {
                result.add(next);
            }
        }
        return result;
    }

    private static List<OcrObservation> debounceOcr(List<OcrObservation> values) {
        List<OcrObservation> result = new ArrayList<>();
        for (OcrObservation next : values) {
            if (result.isEmpty()) {
                result.add(next);
                continue;
            }
            OcrObservation previous = result.get(result.size() - 1);
            if (next.startMs() - previous.endMs() <= OCR_DEBOUNCE_GAP_MS
                    && similarity(previous.text(), next.text()) >= 0.88) {
                String preferred = next.confidence() > previous.confidence() ? next.text()
                        : previous.confidence() > next.confidence() ? previous.text()
                        : longer(previous.text(), next.text());
                result.set(result.size() - 1, new OcrObservation(previous.startMs(),
                        Math.max(previous.endMs(), next.endMs()), preferred,
                        Math.max(previous.confidence(), next.confidence())));
            } else {
                result.add(next);
            }
        }
        return result;
    }

    private static boolean nearOrOverlaps(long leftStart, long leftEnd, long rightStart, long rightEnd) {
        return rightStart <= leftEnd + OCR_ATTACH_TOLERANCE_MS
                && rightEnd + OCR_ATTACH_TOLERANCE_MS >= leftStart;
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

    private static String longer(String left, String right) {
        return left.length() >= right.length() ? left : right;
    }

    private static double clamp(double value) {
        if (Double.isNaN(value)) {
            return 0;
        }
        return Math.max(0, Math.min(1, value));
    }

    private static boolean validRange(long start, long end) {
        return start >= 0 && end >= start;
    }

    public record AsrSegment(long startMs, long endMs, String text, double confidence) {
    }

    public record OcrObservation(long startMs, long endMs, String text, double confidence) {
    }

    public record TimelineEvent(long startMs, long endMs, String speechText, List<String> visualTexts) {
    }

    public record Timeline(List<TimelineEvent> events) {
    }
}
