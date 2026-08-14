package com.videomind.module.agent.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.videomind.module.agent.workflow.AgentWorkflowModels.Critique;
import com.videomind.module.agent.workflow.AgentWorkflowModels.Plan;
import com.videomind.module.agent.workflow.AgentWorkflowModels.Request;
import com.videomind.module.agent.workflow.AgentWorkflowModels.StepResult;
import com.videomind.module.agent.workflow.AgentWorkflowModels.Verdict;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@RequiredArgsConstructor
public class StructuredLlmAgentCritic {
    private final WorkflowDecisionClient decisions;
    private final ObjectMapper objectMapper;

    public Critique review(Request request, Plan plan, List<StepResult> results, int replans) {
        String system = """
                你是 VideoMind 的有界证据 Critic。只返回 JSON，不要 Markdown，不要思维过程。
                格式：{"verdict":"ACCEPT|REPLAN|FAIL","reason":"简短可审计原因"}。
                无证据不得 ACCEPT；只允许通过 REPLAN 建议改写检索，不得建议联网或执行代码。
                """;
        long evidenceCount = results.stream().mapToLong(value -> value.evidence().size()).sum();
        String user = "问题：" + request.question() + "\n路线：" + plan.route()
                + "\n证据条数：" + evidenceCount + "\n已重规划次数：" + replans;
        return parse(decisions.decide(system, user), evidenceCount);
    }

    Critique parse(String raw, long evidenceCount) {
        try {
            JsonNode root = objectMapper.readTree(WorkflowJson.object(raw));
            Verdict verdict = Verdict.valueOf(root.path("verdict").asText(""));
            String reason = root.path("reason").asText("").trim();
            if (!StringUtils.hasText(reason) || verdict == Verdict.ACCEPT && evidenceCount == 0) {
                throw new IllegalArgumentException("WORKFLOW_CRITIQUE_INVALID");
            }
            return new Critique(verdict, reason);
        } catch (IllegalArgumentException known) {
            throw known;
        } catch (Exception invalid) {
            throw new IllegalArgumentException("WORKFLOW_CRITIQUE_JSON_INVALID", invalid);
        }
    }
}
