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
    public Critique review(Request request, Plan plan, List<StepResult> results, int replans) {
        for (StepResult result : results) {
            if (isRetrieval(result.tool()) && result.evidence().isEmpty()) {
                return retryOrFail(request, replans, "检索子问题缺少证据：" + result.stepId());
            }
        }
        List<Evidence> evidence = results.stream().flatMap(value -> value.evidence().stream()).toList();
        if (evidence.isEmpty()) {
            return retryOrFail(request, replans, "当前计划没有取得可引用证据");
        }
        Long videoKnowledgeBaseId = request.knowledgeBaseIds().isEmpty()
                ? null : request.knowledgeBaseIds().get(0);
        Map<String, Evidence> byId = new HashMap<>();
        for (Evidence value : evidence) {
            if (!complete(value) || videoKnowledgeBaseId != null
                    && videoKnowledgeBaseId.equals(value.knowledgeBaseId()) && !validTime(value)) {
                return retryOrFail(request, replans, "Evidence 来源字段或视频时间范围不完整");
            }
            Evidence previous = byId.putIfAbsent(value.evidenceId(), value);
            if (previous != null && conflicts(previous, value)) {
                return retryOrFail(request, replans, "同一 Evidence ID 出现明显冲突");
            }
        }
        return new Critique(Verdict.ACCEPT, "子问题均有完整、可引用且无明显冲突的证据");
    }

    private Critique retryOrFail(Request request, int replans, String reason) {
        return replans < request.maxReplans() ? new Critique(Verdict.REPLAN, reason + "，需要改写查询")
                : new Critique(Verdict.FAIL, reason + "，已达到重规划上限");
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

    private boolean isRetrieval(String tool) {
        return tool != null && tool.endsWith("RETRIEVAL");
    }
}
