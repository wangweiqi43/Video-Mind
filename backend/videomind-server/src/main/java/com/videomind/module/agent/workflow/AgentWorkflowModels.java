package com.videomind.module.agent.workflow;

import com.videomind.module.knowledge.retrieval.Evidence;
import java.util.List;

public final class AgentWorkflowModels {
    private AgentWorkflowModels() {
    }

    public record Request(Long userId, Long conversationId, List<Long> knowledgeBaseIds,
                          String question, Mode mode, WorkflowObserver observer,
                          WorkflowCancellation cancellation) {
        public Request {
            knowledgeBaseIds = knowledgeBaseIds == null ? List.of() : List.copyOf(knowledgeBaseIds);
            mode = mode == null ? Mode.STANDARD : mode;
            observer = observer == null ? WorkflowObserver.NOOP : observer;
            cancellation = cancellation == null ? WorkflowCancellation.NONE : cancellation;
        }

        public Request(Long userId, Long conversationId, List<Long> knowledgeBaseIds,
                       String question, Mode mode, WorkflowObserver observer) {
            this(userId, conversationId, knowledgeBaseIds, question, mode, observer, WorkflowCancellation.NONE);
        }

        public Request(Long userId, Long conversationId, List<Long> knowledgeBaseIds,
                       String question, Mode mode) {
            this(userId, conversationId, knowledgeBaseIds, question, mode,
                    WorkflowObserver.NOOP, WorkflowCancellation.NONE);
        }

        public int maxToolCalls() {
            return mode == Mode.DEEP ? 6 : 2;
        }

        public int maxReplans() {
            return mode == Mode.DEEP ? 2 : 1;
        }

        public java.time.Duration deadline() {
            return java.time.Duration.ofSeconds(mode == Mode.DEEP ? 60 : 20);
        }
    }

    public enum Mode {
        STANDARD,
        DEEP
    }

    public record Plan(String route, List<Step> steps, int generation) {
        public Plan {
            steps = List.copyOf(steps);
        }
    }

    public record Step(String id, String tool, String input) {
    }

    public record StepResult(String stepId, String tool, List<Evidence> evidence, String observation) {
        public StepResult {
            evidence = evidence == null ? List.of() : List.copyOf(evidence);
        }

        public StepResult(String stepId, List<Evidence> evidence) {
            this(stepId, null, evidence, null);
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
