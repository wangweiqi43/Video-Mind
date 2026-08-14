package com.videomind.module.knowledge.retrieval;

import com.videomind.common.exception.BizException;
import com.videomind.module.knowledge.embedding.EmbeddingClient;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class HybridRetrievalService {
    static final int RECALL_LIMIT = 40;
    static final int RRF_K = 60;
    static final int RRF_LIMIT = 20;
    static final int RERANK_LIMIT = 10;
    static final int FINAL_LIMIT = 6;
    private static final String UNAVAILABLE_MESSAGE = "知识检索服务暂时不可用，请稍后重试";

    private final EmbeddingClient embeddingClient;
    private final HybridSearchGateway searchGateway;
    private final RerankClient rerankClient;
    private final MeterRegistry meterRegistry;

    public List<Evidence> retrieve(Long userId, List<Long> knowledgeBaseIds, String query) {
        if (knowledgeBaseIds == null || knowledgeBaseIds.isEmpty()) {
            return List.of();
        }

        Recall keyword = keywordRecall(userId, knowledgeBaseIds, query);
        Recall semantic = semanticRecall(userId, knowledgeBaseIds, query);
        if (!keyword.succeeded() && !semantic.succeeded()) {
            Counter.builder("videomind.knowledge.retrieval.failures")
                    .description("Knowledge retrieval requests where both recall paths failed")
                    .register(meterRegistry).increment();
            log.warn("Knowledge retrieval unavailable because BM25 and kNN both failed");
            throw new BizException(503, UNAVAILABLE_MESSAGE);
        }

        List<Ranked> fused = rrf(keyword.candidates(), semantic.candidates()).stream()
                .limit(RRF_LIMIT).toList();
        RetrievalMode recallMode = recallMode(keyword.succeeded(), semantic.succeeded());
        if (fused.isEmpty()) {
            recordMode(recallMode, keyword.succeeded(), semantic.succeeded(), false);
            return List.of();
        }

        List<RerankClient.RerankScore> reranked;
        try {
            reranked = rerankClient.rerank(query,
                    fused.stream().map(value -> value.candidate().content()).toList(),
                    Math.min(RERANK_LIMIT, fused.size()));
        } catch (Exception failure) {
            log.warn("BGE rerank failed; using RRF fallback, failureType={}",
                    failure.getClass().getSimpleName());
            return rrfFallback(fused, keyword.succeeded(), semantic.succeeded());
        }
        if (!validRerankScores(reranked, fused.size())) {
            log.warn("BGE rerank returned invalid indexes or scores; using RRF fallback");
            return rrfFallback(fused, keyword.succeeded(), semantic.succeeded());
        }

        recordMode(recallMode, keyword.succeeded(), semantic.succeeded(), true);
        return finalizeEvidence(fused, reranked.stream().limit(RERANK_LIMIT).toList());
    }

    private Recall keywordRecall(Long userId, List<Long> knowledgeBaseIds, String query) {
        try {
            return Recall.success(searchGateway.keywordSearch(userId, knowledgeBaseIds, query, RECALL_LIMIT));
        } catch (Exception failure) {
            log.warn("BM25 recall failed; attempting kNN-only retrieval, failureType={}",
                    failure.getClass().getSimpleName());
            return Recall.failure();
        }
    }

    private Recall semanticRecall(Long userId, List<Long> knowledgeBaseIds, String query) {
        try {
            float[] vector = embeddingClient.embed(query);
            if (vector == null || vector.length == 0) {
                throw new IllegalStateException("EMPTY_EMBEDDING");
            }
            return Recall.success(searchGateway.vectorSearch(
                    userId, knowledgeBaseIds, vector, RECALL_LIMIT));
        } catch (Exception failure) {
            log.warn("Embedding or kNN recall failed; attempting BM25-only retrieval, failureType={}",
                    failure.getClass().getSimpleName());
            return Recall.failure();
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
        int limit = Math.min(RECALL_LIMIT, candidates.size());
        for (int index = 0; index < limit; index++) {
            RetrievalCandidate candidate = candidates.get(index);
            if (candidate != null) {
                scores.merge(candidate, 1.0 / (RRF_K + index + 1), Double::sum);
            }
        }
    }

    private boolean validRerankScores(List<RerankClient.RerankScore> scores, int candidateCount) {
        if (scores == null || scores.isEmpty()) {
            return false;
        }
        Set<Integer> seen = new HashSet<>();
        for (RerankClient.RerankScore score : scores) {
            if (score == null || score.index() < 0 || score.index() >= candidateCount
                    || !Double.isFinite(score.score()) || !seen.add(score.index())) {
                return false;
            }
        }
        return true;
    }

    private List<Evidence> rrfFallback(List<Ranked> fused, boolean keywordSucceeded, boolean semanticSucceeded) {
        recordMode(RetrievalMode.RRF_ONLY, keywordSucceeded, semanticSucceeded, false);
        double maxRrf = fused.get(0).rrfScore();
        return fused.stream().limit(FINAL_LIMIT)
                .map(ranked -> evidence(ranked, 0, maxRrf == 0 ? 0 : ranked.rrfScore() / maxRrf))
                .toList();
    }

    private List<Evidence> finalizeEvidence(List<Ranked> fused, List<RerankClient.RerankScore> reranked) {
        double minBge = reranked.stream().mapToDouble(RerankClient.RerankScore::score).min().orElse(0);
        double maxBge = reranked.stream().mapToDouble(RerankClient.RerankScore::score).max().orElse(0);
        double maxRrf = reranked.stream().mapToDouble(value -> fused.get(value.index()).rrfScore())
                .max().orElse(1);
        List<Evidence> values = new ArrayList<>();
        for (RerankClient.RerankScore score : reranked) {
            Ranked ranked = fused.get(score.index());
            double normalizedBge = normalize(score.score(), minBge, maxBge);
            double normalizedRrf = maxRrf == 0 ? 0 : ranked.rrfScore() / maxRrf;
            values.add(evidence(ranked, score.score(), 0.8 * normalizedBge + 0.2 * normalizedRrf));
        }
        return values.stream().sorted(Comparator.comparingDouble(Evidence::finalScore).reversed()
                        .thenComparing(Evidence::evidenceId))
                .limit(FINAL_LIMIT).toList();
    }

    private Evidence evidence(Ranked ranked, double rerankScore, double finalScore) {
        RetrievalCandidate candidate = ranked.candidate();
        return new Evidence("ev-" + candidate.embeddingId(), candidate.knowledgeBaseId(),
                candidate.documentId(), candidate.documentVersionId(), candidate.chunkIndex(),
                candidate.parentIndex(), candidate.title(), candidate.heading(), candidate.content(),
                candidate.parentContent(), candidate.startMs(), candidate.endMs(), ranked.rrfScore(),
                rerankScore, finalScore);
    }

    private RetrievalMode recallMode(boolean keywordSucceeded, boolean semanticSucceeded) {
        return keywordSucceeded && semanticSucceeded ? RetrievalMode.FULL_HYBRID
                : keywordSucceeded ? RetrievalMode.BM25_ONLY : RetrievalMode.KNN_ONLY;
    }

    private void recordMode(RetrievalMode mode, boolean keywordSucceeded,
                            boolean semanticSucceeded, boolean rerankApplied) {
        Counter.builder("videomind.knowledge.retrieval.mode")
                .description("Knowledge retrieval completion mode")
                .tag("mode", mode.name()).register(meterRegistry).increment();
        log.info("Knowledge retrieval completed, mode={}, bm25Succeeded={}, knnSucceeded={}, rerankApplied={}",
                mode, keywordSucceeded, semanticSucceeded, rerankApplied);
    }

    private double normalize(double value, double min, double max) {
        return max == min ? 1 : (value - min) / (max - min);
    }

    enum RetrievalMode {
        FULL_HYBRID,
        BM25_ONLY,
        KNN_ONLY,
        RRF_ONLY
    }

    private record Recall(boolean succeeded, List<RetrievalCandidate> candidates) {
        private static Recall success(List<RetrievalCandidate> candidates) {
            return new Recall(true, candidates == null ? List.of() : candidates);
        }

        private static Recall failure() {
            return new Recall(false, List.of());
        }
    }

    private record Ranked(RetrievalCandidate candidate, double rrfScore) { }
}
