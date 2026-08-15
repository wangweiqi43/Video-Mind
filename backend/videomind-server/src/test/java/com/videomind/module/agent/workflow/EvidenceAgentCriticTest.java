package com.videomind.module.agent.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.videomind.module.agent.workflow.AgentWorkflowModels.Mode;
import com.videomind.module.agent.workflow.AgentWorkflowModels.Plan;
import com.videomind.module.agent.workflow.AgentWorkflowModels.Request;
import com.videomind.module.agent.workflow.AgentWorkflowModels.Step;
import com.videomind.module.agent.workflow.AgentWorkflowModels.StepResult;
import com.videomind.module.agent.workflow.AgentWorkflowModels.Verdict;
import com.videomind.module.knowledge.retrieval.Evidence;
import java.util.List;
import org.junit.jupiter.api.Test;

class EvidenceAgentCriticTest {
    private final EvidenceAgentCritic critic = new EvidenceAgentCritic();
    private final Request request = new Request(7L, 51L, List.of(10L, 20L), "q", Mode.DEEP);

    @Test
    void acceptsCompleteVideoAndDocumentEvidence() {
        var result = critic.review(request, plan(), List.of(
                result("video", "VIDEO_TIMELINE_RETRIEVAL", evidence("ev-v", 10L, "video", 0L, 1000L)),
                result("docs", "USER_DOCUMENT_RETRIEVAL", evidence("ev-d", 20L, "doc", null, null))), 0);

        assertThat(result.verdict()).isEqualTo(Verdict.ACCEPT);
    }

    @Test
    void missingVideoTimeReplansAndFailsAtTheBound() {
        List<StepResult> results = List.of(result("video", "VIDEO_TIMELINE_RETRIEVAL",
                evidence("ev-v", 10L, "video", null, null)));

        assertThat(critic.review(request, plan(), results, 0).verdict()).isEqualTo(Verdict.REPLAN);
        assertThat(critic.review(request, plan(), results, 2).verdict()).isEqualTo(Verdict.FAIL);
    }

    @Test
    void uncoveredRetrievalStepCannotPass() {
        StepResult empty = new StepResult("docs", "USER_DOCUMENT_RETRIEVAL", List.of(), null);

        assertThat(critic.review(request, plan(), List.of(empty), 0).verdict()).isEqualTo(Verdict.REPLAN);
    }

    @Test
    void conflictingDuplicateEvidenceIdsCannotPass() {
        Evidence first = evidence("same", 20L, "first", null, null);
        Evidence second = evidence("same", 20L, "second", null, null);

        assertThat(critic.review(request, plan(), List.of(
                new StepResult("all", "ALL_SCOPE_HYBRID_RETRIEVAL", List.of(first, second), null)), 0)
                .verdict()).isEqualTo(Verdict.REPLAN);
    }

    private Plan plan() {
        return new Plan("test", List.of(new Step("all", "ALL_SCOPE_HYBRID_RETRIEVAL", "q")), 0);
    }

    private StepResult result(String id, String tool, Evidence evidence) {
        return new StepResult(id, tool, List.of(evidence), null);
    }

    private Evidence evidence(String id, Long knowledgeBaseId, String content, Long start, Long end) {
        return new Evidence(id, knowledgeBaseId, 21L, 22L, 0, 0, "title", "heading",
                content, "parent", start, end, 0.1, 0.2, 0.3);
    }
}
