package com.videomind.module.agent.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.videomind.module.agent.workflow.AgentWorkflowModels.QueryOrigin;
import com.videomind.module.agent.workflow.AgentWorkflowModels.StepResult;
import com.videomind.module.knowledge.retrieval.Evidence;
import java.util.List;
import org.junit.jupiter.api.Test;

class EvidenceRankFusionTest {

    @Test
    void fusesOriginalAndRewrittenQueryResultsByStableEvidenceId() {
        Evidence originalA = evidence("ev-a", 0.2, "original-a");
        Evidence rewrittenA = evidence("ev-a", 0.7, "rewritten-a");
        List<StepResult> steps = List.of(
                step("original", QueryOrigin.ORIGINAL,
                        List.of(originalA, evidence("ev-b", 0.9, "b"))),
                step("rewrite", QueryOrigin.REWRITE_1,
                        List.of(rewrittenA, evidence("ev-c", 0.8, "c"))));

        List<Evidence> result = EvidenceRankFusion.fuse(steps);

        assertThat(result).extracting(Evidence::evidenceId)
                .containsExactly("ev-a", "ev-b", "ev-c");
        assertThat(result.get(0).content()).isEqualTo("rewritten-a");
        assertThat(result.get(0).finalScore()).isEqualTo(1);
        assertThat(result).extracting(Evidence::finalScore)
                .isSortedAccordingTo(java.util.Comparator.reverseOrder());
    }

    private StepResult step(String id, QueryOrigin origin, List<Evidence> evidence) {
        return new StepResult(id, "VIDEO_TIMELINE_RETRIEVAL", id, origin, evidence, null);
    }

    private Evidence evidence(String id, double score, String content) {
        return new Evidence(id, 11L, 21L, 31L, 0, 0, "title", "heading", content,
                "parent", null, null, 0.1, 0.2, score);
    }
}
