package com.videomind.module.knowledge.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class VideoMindReciprocalRankFuserTest {

    @Test
    void sortsEachRouteDeduplicatesByChunkIdAndAccumulatesAcrossRoutes() {
        RetrievalCandidate weakA = candidate("a", 0.2, "weak-a");
        RetrievalCandidate strongA = candidate("a", 0.8, "strong-a");
        RetrievalCandidate vectorA = candidate("a", 0.7, "vector-a");

        var result = VideoMindReciprocalRankFuser.fuse(List.of(
                        List.of(weakA, candidate("b", 0.9, "b"), strongA),
                        List.of(vectorA, candidate("c", 0.95, "c"))),
                60, 40, RetrievalCandidate::chunkId, RetrievalCandidate::retrievalScore);

        assertThat(result).extracting(VideoMindReciprocalRankFuser.Fused::identity)
                .containsExactly("a", "c", "b");
        assertThat(result.get(0).content().content()).isEqualTo("strong-a");
        assertThat(result.get(0).rrfScore()).isEqualTo(2.0 / 62);
        assertThat(result).extracting(VideoMindReciprocalRankFuser.Fused::rrfScore)
                .isSortedAccordingTo(java.util.Comparator.reverseOrder());
    }

    private RetrievalCandidate candidate(String id, double score, String content) {
        return new RetrievalCandidate(id, 11L, 21L, 31L, 0, 0,
                "title", "heading", content, "parent", null, null, score);
    }
}
