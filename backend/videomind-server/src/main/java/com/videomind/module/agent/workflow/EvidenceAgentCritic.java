package com.videomind.module.agent.workflow;

import com.videomind.module.agent.workflow.AgentWorkflowModels.Critique;
import com.videomind.module.agent.workflow.AgentWorkflowModels.Plan;
import com.videomind.module.agent.workflow.AgentWorkflowModels.Request;
import com.videomind.module.agent.workflow.AgentWorkflowModels.StepResult;
import com.videomind.module.agent.workflow.AgentWorkflowModels.Verdict;
import com.videomind.module.knowledge.retrieval.Evidence;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class EvidenceAgentCritic implements AgentCritic {
    @Override
    public Critique review(Request request, Plan plan, List<StepResult> results, int replans,
                           long timeoutMillis) {
        Map<String, Evidence> byId = new HashMap<>();
        for (Evidence value : results.stream().flatMap(result -> result.evidence().stream()).toList()) {
            if (!complete(value) || isVideo(request, value) && !validTime(value)) {
                return new Critique(Verdict.FAIL, "EVIDENCE_FIELDS_INVALID",
                        "证据来源字段或视频时间范围不完整", null, List.of(), List.of());
            }
            Evidence previous = byId.putIfAbsent(value.evidenceId(), value);
            if (previous != null && conflicts(previous, value)) {
                return new Critique(Verdict.FAIL, "EVIDENCE_ID_CONFLICT",
                        "同一证据 ID 出现冲突内容", null, List.of(), List.of());
            }
        }
        return new Critique(Verdict.ACCEPT, "EVIDENCE_STRUCTURE_VALID",
                "候选证据结构校验通过", null, List.of(), List.of());
    }

    private boolean isVideo(Request request, Evidence value) {
        return request.scope().videoKnowledgeBaseId() != null
                && request.scope().videoKnowledgeBaseId().equals(value.knowledgeBaseId());
    }

    private boolean complete(Evidence value) {
        return value != null && StringUtils.hasText(value.evidenceId()) && value.knowledgeBaseId() != null
                && value.documentId() != null && value.documentVersionId() != null
                && value.chunkIndex() >= 0 && StringUtils.hasText(value.content());
    }

    private boolean validTime(Evidence value) {
        return value.startMs() != null && value.endMs() != null
                && value.startMs() >= 0 && value.endMs() >= value.startMs();
    }

    private boolean conflicts(Evidence first, Evidence second) {
        return !java.util.Objects.equals(first.knowledgeBaseId(), second.knowledgeBaseId())
                || !java.util.Objects.equals(first.documentId(), second.documentId())
                || !java.util.Objects.equals(first.documentVersionId(), second.documentVersionId())
                || first.chunkIndex() != second.chunkIndex()
                || !java.util.Objects.equals(first.content(), second.content())
                || !java.util.Objects.equals(first.startMs(), second.startMs())
                || !java.util.Objects.equals(first.endMs(), second.endMs());
    }
}
