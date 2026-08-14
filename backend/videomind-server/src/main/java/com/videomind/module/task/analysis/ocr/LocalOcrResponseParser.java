package com.videomind.module.task.analysis.ocr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.videomind.common.exception.BizException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class LocalOcrResponseParser {
    private final ObjectMapper objectMapper;

    public LocalOcrResponseParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public FrameOcrClient.OcrText parse(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            List<String> texts = new ArrayList<>();
            List<Double> scores = new ArrayList<>();
            collect(root, texts, scores);
            String text = texts.stream().filter(StringUtils::hasText).distinct()
                    .reduce((left, right) -> left + "\n" + right).orElse("");
            double confidence = scores.stream().mapToDouble(Double::doubleValue).average().orElse(1.0);
            return new FrameOcrClient.OcrText(text, Math.max(0, Math.min(1, confidence)));
        } catch (IOException exception) {
            throw new BizException(502, "本机 OCR 返回了无法解析的 JSON");
        }
    }

    private void collect(JsonNode node, List<String> texts, List<Double> scores) {
        if (node == null || node.isNull()) {
            return;
        }
        if (node.isObject()) {
            addText(node.path("text"), texts);
            addText(node.path("rec_text"), texts);
            JsonNode recTexts = node.path("rec_texts");
            if (recTexts.isArray()) {
                recTexts.forEach(value -> addText(value, texts));
            }
            addScore(node.path("confidence"), scores);
            addScore(node.path("score"), scores);
            JsonNode recScores = node.path("rec_scores");
            if (recScores.isArray()) {
                recScores.forEach(value -> addScore(value, scores));
            }
            node.fields().forEachRemaining(entry -> {
                if (!entry.getKey().equals("text") && !entry.getKey().equals("rec_text")
                        && !entry.getKey().equals("rec_texts") && !entry.getKey().equals("confidence")
                        && !entry.getKey().equals("score") && !entry.getKey().equals("rec_scores")) {
                    collect(entry.getValue(), texts, scores);
                }
            });
        } else if (node.isArray()) {
            node.forEach(child -> collect(child, texts, scores));
        }
    }

    private void addText(JsonNode node, List<String> texts) {
        if (node.isTextual() && StringUtils.hasText(node.asText())) {
            texts.add(node.asText().strip());
        }
    }

    private void addScore(JsonNode node, List<Double> scores) {
        if (node.isNumber()) {
            scores.add(node.asDouble());
        }
    }
}
