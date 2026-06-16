package com.videomind.module.knowledge.chunk;

import com.videomind.config.KnowledgeProperties;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@RequiredArgsConstructor
public class FixedLengthTextChunker implements TextChunker {

    private final KnowledgeProperties knowledgeProperties;

    @Override
    public List<String> split(String text) {
        if (!StringUtils.hasText(text)) {
            return List.of();
        }
        String normalized = text.replace("\r\n", "\n").trim();
        int chunkSize = Math.max(100, knowledgeProperties.getChunkSize());
        int overlap = Math.max(0, Math.min(knowledgeProperties.getChunkOverlap(), chunkSize / 2));

        List<String> chunks = new ArrayList<>();
        int start = 0;
        while (start < normalized.length()) {
            int end = Math.min(start + chunkSize, normalized.length());
            chunks.add(normalized.substring(start, end).trim());
            if (end == normalized.length()) {
                break;
            }
            start = Math.max(end - overlap, start + 1);
        }
        return chunks;
    }
}

