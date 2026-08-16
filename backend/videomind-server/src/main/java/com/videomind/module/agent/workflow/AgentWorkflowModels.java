package com.videomind.module.agent.workflow;

import com.videomind.module.knowledge.retrieval.Evidence;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

public final class AgentWorkflowModels {
    private AgentWorkflowModels() {
    }

    public record KnowledgeScope(Long videoKnowledgeBaseId, List<Long> documentKnowledgeBaseIds) {
        public KnowledgeScope {
            documentKnowledgeBaseIds = documentKnowledgeBaseIds == null
                    ? List.of() : List.copyOf(documentKnowledgeBaseIds);
        }

        public static KnowledgeScope fromOrderedIds(List<Long> values) {
            if (values == null || values.isEmpty()) return new KnowledgeScope(null, List.of());
            return new KnowledgeScope(values.get(0), values.size() == 1
                    ? List.of() : values.subList(1, values.size()));
        }

        public List<Long> allIds() {
            LinkedHashSet<Long> ids = new LinkedHashSet<>();
            if (videoKnowledgeBaseId != null) ids.add(videoKnowledgeBaseId);
            ids.addAll(documentKnowledgeBaseIds);
            return List.copyOf(ids);
        }
    }

    public record ConversationSnapshot(String summary, List<String> recentTurns) {
        public static final ConversationSnapshot EMPTY = new ConversationSnapshot("", List.of());

        public ConversationSnapshot {
            summary = summary == null ? "" : summary;
            recentTurns = recentTurns == null ? List.of() : List.copyOf(recentTurns);
        }
    }

    public record Request(Long userId, Long conversationId, KnowledgeScope scope,
                          ConversationSnapshot conversation, String question,
                          WorkflowObserver observer, WorkflowCancellation cancellation) {
        public Request {
            scope = scope == null ? new KnowledgeScope(null, List.of()) : scope;
            conversation = conversation == null ? ConversationSnapshot.EMPTY : conversation;
            observer = observer == null ? WorkflowObserver.NOOP : observer;
            cancellation = cancellation == null ? WorkflowCancellation.NONE : cancellation;
        }

        public Request(Long userId, Long conversationId, KnowledgeScope scope,
                       ConversationSnapshot conversation, String question) {
            this(userId, conversationId, scope, conversation, question,
                    WorkflowObserver.NOOP, WorkflowCancellation.NONE);
        }

        public Request(Long userId, Long conversationId, List<Long> knowledgeBaseIds, String question) {
            this(userId, conversationId, KnowledgeScope.fromOrderedIds(knowledgeBaseIds),
                    ConversationSnapshot.EMPTY, question, WorkflowObserver.NOOP, WorkflowCancellation.NONE);
        }

        public List<Long> knowledgeBaseIds() {
            return scope.allIds();
        }

        public int maxToolCalls() {
            return 6;
        }

        public int maxReplans() {
            return 1;
        }

        public Duration deadline() {
            return Duration.ofSeconds(100);
        }
    }

    public enum Route {
        DIRECT_CONVERSATION,
        VIDEO_RAG,
        DOCUMENT_RAG,
        MIXED_RAG,
        OUT_OF_SCOPE
    }

    public record Plan(Route route, List<Step> steps, int generation, String reasonCode) {
        public Plan {
            steps = steps == null ? List.of() : List.copyOf(steps);
            reasonCode = reasonCode == null ? "UNSPECIFIED" : reasonCode;
        }

        public Plan(Route route, List<Step> steps, int generation) {
            this(route, steps, generation, "UNSPECIFIED");
        }
    }

    public enum QueryOrigin {
        ORIGINAL,
        REWRITE_1,
        REWRITE_2
    }

    public record Step(String id, String tool, String input, QueryOrigin queryOrigin) {
        public Step {
            queryOrigin = queryOrigin == null ? QueryOrigin.ORIGINAL : queryOrigin;
        }

        public Step(String id, String tool, String input) {
            this(id, tool, input, QueryOrigin.ORIGINAL);
        }
    }

    public record StepResult(String stepId, String tool, String query, QueryOrigin queryOrigin,
                             List<Evidence> evidence, String observation) {
        public StepResult {
            evidence = evidence == null ? List.of() : List.copyOf(evidence);
            queryOrigin = queryOrigin == null ? QueryOrigin.ORIGINAL : queryOrigin;
        }

        public StepResult(String stepId, String tool, List<Evidence> evidence, String observation) {
            this(stepId, tool, null, QueryOrigin.ORIGINAL, evidence, observation);
        }

        public StepResult(String stepId, List<Evidence> evidence) {
            this(stepId, null, null, QueryOrigin.ORIGINAL, evidence, null);
        }
    }

    public record QuerySet(String originalQuery, List<String> rewrittenQueries) {
        public QuerySet {
            rewrittenQueries = rewrittenQueries == null ? List.of() : List.copyOf(rewrittenQueries);
        }

        public static QuerySet originalOnly(String original) {
            return new QuerySet(original, List.of());
        }
    }

    public enum Verdict {
        ACCEPT,
        REPLAN,
        OUT_OF_SCOPE,
        INSUFFICIENT_EVIDENCE,
        FAIL
    }

    public record Critique(Verdict verdict, String reasonCode, String reason, QuerySet querySet,
                           List<String> protectedTerms, List<String> acceptedEvidenceIds) {
        public Critique {
            reasonCode = reasonCode == null ? "UNSPECIFIED" : reasonCode;
            reason = reason == null ? "" : reason;
            protectedTerms = protectedTerms == null ? List.of() : List.copyOf(protectedTerms);
            acceptedEvidenceIds = acceptedEvidenceIds == null ? List.of() : List.copyOf(acceptedEvidenceIds);
        }

        public Critique(Verdict verdict, String reason) {
            this(verdict, "UNSPECIFIED", reason, null, List.of(), List.of());
        }
    }

    public enum Status {
        COMPLETED,
        DIRECT_CONVERSATION,
        OUT_OF_SCOPE,
        INSUFFICIENT_EVIDENCE,
        VERIFICATION_UNAVAILABLE,
        TOOL_BUDGET_EXCEEDED,
        DEADLINE_EXCEEDED
    }

    public record Result(Status status, Plan finalPlan, List<StepResult> steps,
                         List<Evidence> candidates, List<Evidence> evidence,
                         int replans, int toolCalls, String reason) {
        public Result {
            steps = steps == null ? List.of() : List.copyOf(steps);
            candidates = candidates == null ? List.of() : List.copyOf(candidates);
            evidence = evidence == null ? List.of() : List.copyOf(evidence);
        }

        public Result(Status status, Plan finalPlan, List<StepResult> steps,
                      List<Evidence> evidence, int replans, int toolCalls, String reason) {
            this(status, finalPlan, steps, evidence, evidence, replans, toolCalls, reason);
        }

        public static List<Evidence> distinctEvidence(List<StepResult> steps) {
            List<Evidence> values = new ArrayList<>();
            LinkedHashSet<String> ids = new LinkedHashSet<>();
            for (StepResult step : steps) {
                for (Evidence evidence : step.evidence()) {
                    if (evidence != null && ids.add(evidence.evidenceId())) values.add(evidence);
                }
            }
            return List.copyOf(values);
        }
    }
}
