package com.videomind.module.knowledge.embedding;

import com.videomind.config.KnowledgeProperties;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "videomind.ai.embedding", name = "mode", havingValue = "mock", matchIfMissing = true)
@RequiredArgsConstructor
public class MockEmbeddingClient implements EmbeddingClient {

    private final KnowledgeProperties knowledgeProperties;

    @Override
    public float[] embed(String text) {
        int dim = knowledgeProperties.getEmbeddingDim();
        float[] vector = new float[dim];
        byte[] bytes = text == null ? new byte[0] : text.getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < bytes.length; i++) {
            int index = i % dim;
            vector[index] += (bytes[i] & 0xff) / 255.0f;
        }
        normalize(vector);
        return vector;
    }

    private void normalize(float[] vector) {
        double sum = 0;
        for (float value : vector) {
            sum += value * value;
        }
        if (sum == 0) {
            vector[0] = 1.0f;
            return;
        }
        float norm = (float) Math.sqrt(sum);
        for (int i = 0; i < vector.length; i++) {
            vector[i] = vector[i] / norm;
        }
    }
}
