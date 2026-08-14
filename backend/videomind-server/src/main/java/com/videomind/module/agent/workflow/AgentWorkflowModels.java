package com.videomind.module.agent.workflow;

import com.videomind.module.knowledge.retrieval.Evidence;
import java.time.Duration;
import java.util.List;

public final class AgentWorkflowModels {
    private AgentWorkflowModels() {
    }

    public record Request(Long userId, List<Long> knowledgeBaseIds, String question,
                          int maxToolCalls, Duration deadline) {
        public Request {
            knowledgeBaseIds = knowledgeBaseIds == null ? List.of() : List.copyOf(knowledgeBaseIds);
            maxToolCalls = maxToolCalls <= 0 ? 4 : maxToolCalls;
            deadline = deadline == null ? Duration.ofSeconds(30) : deadline;
        }
    }

    public record Plan(String route, List<Step> steps, int generation) {
        public Plan {
            steps = List.copyOf(steps);
        }
    }

    public record Step(String id, String tool, String input) {
    }

    public record StepResult(String stepId, List<Evidence> evidence) {
        public StepResult {
            evidence = evidence == null ? List.of() : List.copyOf(evidence);
        }
    }

    public enum Verdict {
        ACCEPT,
        REPLAN,
        FAIL
    }

    public record Critique(Verdict verdict, String reason) {
    }

    public enum Status {
        COMPLETED,
        INSUFFICIENT_EVIDENCE,
        TOOL_BUDGET_EXCEEDED,
        DEADLINE_EXCEEDED
    }

    public record Result(Status status, Plan finalPlan, List<StepResult> steps,
                         List<Evidence> evidence, int replans, int toolCalls, String reason) {
    }
}
