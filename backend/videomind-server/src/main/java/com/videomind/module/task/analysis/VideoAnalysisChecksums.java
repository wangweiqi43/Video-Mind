package com.videomind.module.task.analysis;

import com.videomind.module.knowledge.timeline.TimelineFusionService.OcrObservation;
import com.videomind.module.task.analysis.dto.AsrResult;
import com.videomind.module.task.analysis.dto.AsrSegmentResult;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

final class VideoAnalysisChecksums {
    private VideoAnalysisChecksums() {
    }

    static String asr(AsrResult value) {
        StringBuilder joined = new StringBuilder(safe(value.getLanguage())).append(':').append(safe(value.getText()));
        for (AsrSegmentResult segment : value.getSegments()) {
            joined.append(':').append(segment.startMs()).append(':').append(segment.endMs()).append(':')
                    .append(safe(segment.text())).append(':').append(segment.speakerId());
        }
        return sha256(joined.toString());
    }

    static String ocr(List<OcrObservation> values) {
        StringBuilder joined = new StringBuilder();
        for (OcrObservation value : values) {
            joined.append(':').append(value.startMs()).append(':').append(value.endMs()).append(':')
                    .append(safe(value.text())).append(':').append(value.confidence());
        }
        return sha256(joined.toString());
    }

    static String sha256(String value) {
        return sha256(value.getBytes(StandardCharsets.UTF_8));
    }

    static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    static String safe(String value) {
        return value == null ? "" : value;
    }
}
