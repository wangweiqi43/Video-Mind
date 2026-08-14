package com.videomind.module.knowledge.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import com.videomind.module.knowledge.embedding.EmbeddingClient;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class HybridRetrievalServiceTest {

    @Test
    void candidateIdentityUsesOnlyChildEmbeddingId() {
        RetrievalCandidate first = candidate("same", 1);
        RetrievalCandidate second = new RetrievalCandidate("same", 999L, 888L, 777L,
                55, 44, "different", "different", "different", "different", null, null);
        assertThat(first).isEqualTo(second);
        assertThat(first.hashCode()).isEqualTo(second.hashCode());
    }

    @Test
    void performsFortyFortyRrfTwentyRerankTenAndReturnsSix() {
        RecordingGateway gateway = new RecordingGateway();
        RecordingReranker reranker = new RecordingReranker();
        EmbeddingClient embedding = text -> new float[] {1, 2, 3};
        HybridRetrievalService service = new HybridRetrievalService(embedding, gateway, reranker);

        List<Evidence> evidence = service.retrieve(7L, List.of(11L, 12L), "如何部署");

        assertThat(gateway.keywordLimit).isEqualTo(40);
        assertThat(gateway.vectorLimit).isEqualTo(40);
        assertThat(reranker.documents).hasSize(20);
        assertThat(reranker.topN).isEqualTo(10);
        assertThat(evidence).hasSize(6);
        assertThat(evidence).isSortedAccordingTo(
                java.util.Comparator.comparingDouble(Evidence::finalScore).reversed()
                        .thenComparing(Evidence::evidenceId));
        assertThat(evidence).allMatch(value -> value.evidenceId().startsWith("ev-"));
    }

    @Test
    void overlappingKeywordAndVectorHitsGainRrfPriorityWithoutDocumentLevelDeduplication() {
        HybridSearchGateway gateway = new HybridSearchGateway() {
            public List<RetrievalCandidate> keywordSearch(Long user, List<Long> scope, String query, int limit) {
                return List.of(candidate("shared", 0), candidate("keyword", 1));
            }

            public List<RetrievalCandidate> vectorSearch(Long user, List<Long> scope, float[] vector, int limit) {
                return List.of(candidate("shared", 9), candidate("vector", 2));
            }
        };
        RerankClient reranker = (query, documents, topN) -> List.of(
                new RerankClient.RerankScore(0, 1), new RerankClient.RerankScore(1, 0.5),
                new RerankClient.RerankScore(2, 0.4));
        List<Evidence> result = new HybridRetrievalService(text -> new float[] {1}, gateway, reranker)
                .retrieve(7L, List.of(11L), "question");
        assertThat(result.get(0).evidenceId()).isEqualTo("ev-shared");
        assertThat(result).extracting(Evidence::evidenceId).contains("ev-keyword", "ev-vector");
    }

    private static RetrievalCandidate candidate(String id, int index) {
        return new RetrievalCandidate(id, 11L, 21L, 31L, index, index / 2,
                "title", "heading", "content-" + id, "parent-" + index,
                (long) index * 1000, (long) (index + 1) * 1000);
    }

    private static class RecordingGateway implements HybridSearchGateway {
        int keywordLimit;
        int vectorLimit;

        public List<RetrievalCandidate> keywordSearch(Long user, List<Long> scope, String query, int limit) {
            keywordLimit = limit;
            return candidates("k", 0, 50);
        }

        public List<RetrievalCandidate> vectorSearch(Long user, List<Long> scope, float[] vector, int limit) {
            vectorLimit = limit;
            List<RetrievalCandidate> values = new ArrayList<>();
            values.addAll(candidates("k", 0, 10));
            values.addAll(candidates("v", 0, 40));
            return values;
        }
    }

    private static class RecordingReranker implements RerankClient {
        List<String> documents;
        int topN;

        public List<RerankScore> rerank(String query, List<String> documents, int topN) {
            this.documents = documents;
            this.topN = topN;
            List<RerankScore> scores = new ArrayList<>();
            for (int index = 0; index < topN; index++) {
                scores.add(new RerankScore(index, topN - index));
            }
            return scores;
        }
    }

    private static List<RetrievalCandidate> candidates(String prefix, int start, int count) {
        List<RetrievalCandidate> values = new ArrayList<>();
        for (int index = start; index < start + count; index++) {
            values.add(candidate(prefix + index, index));
        }
        return values;
    }
}
