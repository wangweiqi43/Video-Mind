package com.videomind.module.agent.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.videomind.module.agent.workflow.AgentWorkflowModels.Plan;
import com.videomind.module.agent.workflow.AgentWorkflowModels.Request;
import com.videomind.module.agent.workflow.AgentWorkflowModels.Route;
import com.videomind.module.agent.workflow.AgentWorkflowModels.Step;
import com.videomind.module.agent.workflow.AgentWorkflowModels.StepResult;
import com.videomind.module.agent.workflow.AgentWorkflowModels.Verdict;
import com.videomind.module.knowledge.retrieval.Evidence;
import java.util.List;
import org.junit.jupiter.api.Test;

class EvidenceAgentCriticTest {
    private final EvidenceAgentCritic critic = new EvidenceAgentCritic();
    private final Request request = new Request(7L, 51L, List.of(10L, 20L), "q");

    @Test
    void acceptsStructurallyCompleteVideoAndDocumentEvidence() {
        var result = critic.review(request, plan(), List.of(
                result("video", "VIDEO_TIMELINE_RETRIEVAL", evidence("ev-v", 10L, "video", 0L, 1000L)),
                result("docs", "USER_DOCUMENT_RETRIEVAL", evidence("ev-d", 20L, "doc", null, null))), 0);

        assertThat(result.verdict()).isEqualTo(Verdict.ACCEPT);
    }

    @Test
    void rejectsVideoEvidenceWithoutARealTimeRange() {
        var critique = critic.review(request, plan(), List.of(result("video", "VIDEO_TIMELINE_RETRIEVAL",
                evidence("ev-v", 10L, "video", null, null))), 0);

        assertThat(critique.verdict()).isEqualTo(Verdict.FAIL);
        assertThat(critique.reasonCode()).isEqualTo("EVIDENCE_FIELDS_INVALID");
    }

    @Test
    void letsTheSemanticCriticDecideWhenRetrievalIsEmpty() {
        StepResult empty = new StepResult("docs", "USER_DOCUMENT_RETRIEVAL", "q",
                AgentWorkflowModels.QueryOrigin.ORIGINAL, List.of(), null);

        assertThat(critic.review(request, plan(), List.of(empty), 0).verdict())
                .isEqualTo(Verdict.ACCEPT);
    }

    @Test
    void rejectsConflictingDuplicateEvidenceIds() {
        Evidence first = evidence("same", 20L, "first", null, null);
        Evidence second = evidence("same", 20L, "second", null, null);

        assertThat(critic.review(request, plan(), List.of(
                new StepResult("docs", "USER_DOCUMENT_RETRIEVAL", "q",
                        AgentWorkflowModels.QueryOrigin.ORIGINAL, List.of(first, second), null)), 0)
                .verdict()).isEqualTo(Verdict.FAIL);
    }

    private Plan plan() {
        return new Plan(Route.MIXED_RAG, List.of(
                new Step("video", "VIDEO_TIMELINE_RETRIEVAL", "q"),
                new Step("docs", "USER_DOCUMENT_RETRIEVAL", "q")), 0);
    }

    private StepResult result(String id, String tool, Evidence evidence) {
        return new StepResult(id, tool, "q", AgentWorkflowModels.QueryOrigin.ORIGINAL,
                List.of(evidence), null);
    }

    private Evidence evidence(String id, Long knowledgeBaseId, String content, Long start, Long end) {
        return new Evidence(id, knowledgeBaseId, 21L, 22L, 0, 0, "title", "heading",
                content, "parent", start, end, 0.1, 0.2, 0.3);
    }
}
