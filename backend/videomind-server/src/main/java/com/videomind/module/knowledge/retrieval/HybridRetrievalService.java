package com.videomind.module.knowledge.retrieval;

import com.videomind.common.exception.BizException;
import com.videomind.module.knowledge.embedding.EmbeddingClient;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class HybridRetrievalService {
    static final int RECALL_LIMIT = 40;
    static final int RRF_K = 60;
    static final int RRF_LIMIT = 20;
    static final int RERANK_LIMIT = 10;
    static final int FINAL_LIMIT = 6;

    private final EmbeddingClient embeddingClient;
    private final HybridSearchGateway searchGateway;
    private final RerankClient rerankClient;

    public List<Evidence> retrieve(Long userId, List<Long> knowledgeBaseIds, String query) {
        if (knowledgeBaseIds == null || knowledgeBaseIds.isEmpty()) {
            return List.of();
        }
        try {
            float[] vector = embeddingClient.embed(query);
            List<RetrievalCandidate> keyword = searchGateway.keywordSearch(
                    userId, knowledgeBaseIds, query, RECALL_LIMIT);
            List<RetrievalCandidate> semantic = searchGateway.vectorSearch(
                    userId, knowledgeBaseIds, vector, RECALL_LIMIT);
            List<Ranked> fused = rrf(keyword, semantic).stream().limit(RRF_LIMIT).toList();
            if (fused.isEmpty()) {
                return List.of();
            }
            List<RerankClient.RerankScore> reranked = rerankClient.rerank(query,
                    fused.stream().map(value -> value.candidate().content()).toList(),
                    Math.min(RERANK_LIMIT, fused.size()));
            return finalizeEvidence(fused, reranked);
        } catch (BizException known) {
            throw known;
        } catch (Exception failure) {
            throw new BizException(503, "知识检索服务暂时不可用，请稍后重试");
        }
    }

    private List<Ranked> rrf(List<RetrievalCandidate> keyword, List<RetrievalCandidate> semantic) {
        Map<RetrievalCandidate, Double> scores = new LinkedHashMap<>();
        accumulate(scores, keyword);
        accumulate(scores, semantic);
        return scores.entrySet().stream()
                .map(entry -> new Ranked(entry.getKey(), entry.getValue()))
                .sorted(Comparator.comparingDouble(Ranked::rrfScore).reversed()
                        .thenComparing(value -> value.candidate().embeddingId()))
                .toList();
    }

    private void accumulate(Map<RetrievalCandidate, Double> scores, List<RetrievalCandidate> candidates) {
        if (candidates == null) {
            return;
        }
        int limit = Math.min(RECALL_LIMIT, candidates.size());
        for (int index = 0; index < limit; index++) {
            scores.merge(candidates.get(index), 1.0 / (RRF_K + index + 1), Double::sum);
        }
    }

    private List<Evidence> finalizeEvidence(List<Ranked> fused, List<RerankClient.RerankScore> reranked) {
        List<RerankClient.RerankScore> valid = reranked == null ? List.of() : reranked.stream()
                .filter(score -> score.index() >= 0 && score.index() < fused.size())
                .limit(RERANK_LIMIT).toList();
        if (valid.isEmpty()) {
            throw new BizException(503, "BGE Reranker 未返回有效结果，请稍后重试");
        }
        double minBge = valid.stream().mapToDouble(RerankClient.RerankScore::score).min().orElse(0);
        double maxBge = valid.stream().mapToDouble(RerankClient.RerankScore::score).max().orElse(0);
        double maxRrf = valid.stream().mapToDouble(value -> fused.get(value.index()).rrfScore())
                .max().orElse(1);
        List<Evidence> values = new ArrayList<>();
        for (RerankClient.RerankScore score : valid) {
            Ranked ranked = fused.get(score.index());
            double normalizedBge = normalize(score.score(), minBge, maxBge);
            double normalizedRrf = maxRrf == 0 ? 0 : ranked.rrfScore() / maxRrf;
            double finalScore = 0.8 * normalizedBge + 0.2 * normalizedRrf;
            RetrievalCandidate candidate = ranked.candidate();
            values.add(new Evidence("ev-" + candidate.embeddingId(), candidate.knowledgeBaseId(),
                    candidate.documentId(), candidate.documentVersionId(), candidate.chunkIndex(),
                    candidate.parentIndex(), candidate.title(), candidate.heading(), candidate.content(),
                    candidate.parentContent(), candidate.startMs(), candidate.endMs(), ranked.rrfScore(),
                    score.score(), finalScore));
        }
        return values.stream().sorted(Comparator.comparingDouble(Evidence::finalScore).reversed()
                        .thenComparing(Evidence::evidenceId))
                .limit(FINAL_LIMIT).toList();
    }

    private double normalize(double value, double min, double max) {
        return max == min ? 1 : (value - min) / (max - min);
    }

    private record Ranked(RetrievalCandidate candidate, double rrfScore) {
    }
}
