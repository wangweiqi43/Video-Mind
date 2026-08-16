package com.videomind.module.agent.workflow;

import com.videomind.config.AgentWorkflowProperties;
import com.videomind.module.agent.workflow.AgentWorkflowModels.Critique;
import com.videomind.module.agent.workflow.AgentWorkflowModels.Plan;
import com.videomind.module.agent.workflow.AgentWorkflowModels.Request;
import com.videomind.module.agent.workflow.AgentWorkflowModels.Result;
import com.videomind.module.agent.workflow.AgentWorkflowModels.Route;
import com.videomind.module.agent.workflow.AgentWorkflowModels.Status;
import com.videomind.module.agent.workflow.AgentWorkflowModels.StepResult;
import com.videomind.module.agent.workflow.AgentWorkflowModels.Verdict;
import com.videomind.module.knowledge.retrieval.Evidence;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class PlannerExecutorCriticWorkflow {
    private final AgentPlanner planner;
    private final AgentExecutor executor;
    private final AgentCritic critic;
    private final WorkflowClock clock;
    private final AgentWorkflowProperties properties;
    private final WorkflowDecisionRunner stages;

    public Result run(Request request) {
        validate(request);
        request.cancellation().check();
        java.time.Instant deadline = clock.now().plus(request.deadline());
        StageBudgets budgets = new StageBudgets(request.deadline(), properties);
        Plan plan = null;
        Critique critique = null;
        List<StepResult> audit = new ArrayList<>();
        Set<String> executedCalls = new LinkedHashSet<>();
        int toolCalls = 0;
        int replans = 0;
        while (true) {
            request.cancellation().check();
            if (expired(deadline)) return terminal(request, Status.DEADLINE_EXCEEDED, plan, audit,
                    List.of(), replans, toolCalls, "PEC 工作流超时");
            long plannerTimeout = budgets.timeoutMillis(Phase.PLANNER);
            if (plannerTimeout <= 0) return terminal(request, Status.VERIFICATION_UNAVAILABLE, plan, audit,
                    List.of(), replans, toolCalls, "Planner 阶段预算耗尽");
            long plannerStart = System.nanoTime();
            try {
                plan = planner.plan(request, plan, critique, replans, plannerTimeout);
            } catch (WorkflowDecisionUnavailableException unavailable) {
                return terminal(request, Status.VERIFICATION_UNAVAILABLE, plan, audit,
                        List.of(), replans, toolCalls, "当前无法验证知识证据，请稍后重试");
            } finally {
                budgets.consume(Phase.PLANNER, System.nanoTime() - plannerStart);
            }
            request.cancellation().check();
            if (budgets.overallExpired() || expired(deadline)) {
                return terminal(request, Status.DEADLINE_EXCEEDED, plan, audit,
                        List.of(), replans, toolCalls, "PEC 工作流超时");
            }
            emit(request, new WorkflowEvent("PLAN", plan.generation(), "plan-" + plan.generation(), null,
                    "COMPLETED", planMessage(plan.route()), 0, List.of()));

            if (plan.route() == Route.OUT_OF_SCOPE) {
                return terminal(request, Status.OUT_OF_SCOPE, plan, audit, List.of(),
                        replans, toolCalls, "问题超出当前视频和所选知识库范围");
            }

            for (var step : plan.steps()) {
                request.cancellation().check();
                if (toolCalls >= maxToolCalls(request)) {
                    return terminal(request, Status.TOOL_BUDGET_EXCEEDED, plan, audit,
                            List.of(), replans, toolCalls, "检索次数超过限制");
                }
                if (expired(deadline)) return terminal(request, Status.DEADLINE_EXCEEDED, plan, audit,
                        List.of(), replans, toolCalls, "PEC 工作流超时");
                String callKey = step.tool() + "\n" + step.input().strip().toLowerCase(java.util.Locale.ROOT);
                if (!executedCalls.add(callKey)) {
                    return terminal(request, Status.VERIFICATION_UNAVAILABLE, plan, audit,
                            List.of(), replans, toolCalls, "规划产生了重复检索，已安全终止");
                }
                long stepStart = System.nanoTime();
                emit(request, new WorkflowEvent("TOOL", plan.generation(), step.id(), step.tool(),
                        "STARTED", toolMessage(step.tool()), 0, List.of()));
                StepResult value;
                long executorTimeout = budgets.timeoutMillis(Phase.EXECUTOR);
                if (executorTimeout <= 0) {
                    return terminal(request, Status.DEADLINE_EXCEEDED, plan, audit,
                            List.of(), replans, toolCalls, "Executor 阶段预算耗尽");
                }
                long executorStart = System.nanoTime();
                try {
                    value = stages.run(() -> executor.execute(request, step), executorTimeout,
                            request.cancellation());
                    request.cancellation().check();
                } catch (RuntimeException failure) {
                    emit(request, new WorkflowEvent("TOOL", plan.generation(), step.id(), step.tool(),
                            "FAILED", "知识检索失败", elapsedMs(stepStart), List.of()));
                    if (isStageTimeout(failure)) {
                        return terminal(request, Status.DEADLINE_EXCEEDED, plan, audit,
                                List.of(), replans, toolCalls, "Executor 阶段预算耗尽");
                    }
                    throw failure;
                } finally {
                    budgets.consume(Phase.EXECUTOR, System.nanoTime() - executorStart);
                }
                audit.add(value);
                toolCalls++;
                emit(request, new WorkflowEvent("TOOL", plan.generation(), step.id(), step.tool(),
                        "COMPLETED", "知识检索完成", elapsedMs(stepStart), value.evidence().stream()
                        .map(Evidence::evidenceId).toList()));
                if (budgets.overallExpired() || expired(deadline)) {
                    return terminal(request, Status.DEADLINE_EXCEEDED, plan, audit,
                            List.of(), replans, toolCalls, "PEC 工作流超时");
                }
            }

            long criticTimeout = budgets.timeoutMillis(Phase.CRITIC);
            if (criticTimeout <= 0) return terminal(request, Status.VERIFICATION_UNAVAILABLE, plan, audit,
                    List.of(), replans, toolCalls, "Critic 阶段预算耗尽");
            long criticStart = System.nanoTime();
            try {
                critique = critic.review(request, plan, List.copyOf(audit), replans, criticTimeout);
            } catch (WorkflowDecisionUnavailableException unavailable) {
                return terminal(request, Status.VERIFICATION_UNAVAILABLE, plan, audit,
                        List.of(), replans, toolCalls, "当前无法验证知识证据，请稍后重试");
            } finally {
                budgets.consume(Phase.CRITIC, System.nanoTime() - criticStart);
            }
            request.cancellation().check();
            if (budgets.overallExpired() || expired(deadline)) {
                return terminal(request, Status.DEADLINE_EXCEEDED, plan, audit,
                        List.of(), replans, toolCalls, "PEC 工作流超时");
            }
            emit(request, new WorkflowEvent("CRITIC", plan.generation(), "critic-" + plan.generation(), null,
                    critique.verdict().name(), critique.reason(), 0, critique.acceptedEvidenceIds()));

            if (critique.verdict() == Verdict.OUT_OF_SCOPE) {
                return terminal(request, Status.OUT_OF_SCOPE, plan, audit, List.of(),
                        replans, toolCalls, critique.reason());
            }
            if (critique.verdict() == Verdict.ACCEPT) {
                List<Evidence> accepted = selectAccepted(audit, critique.acceptedEvidenceIds());
                Status status = plan.route() == Route.DIRECT_CONVERSATION
                        ? Status.DIRECT_CONVERSATION : Status.COMPLETED;
                return terminal(request, status, plan, audit, accepted,
                        replans, toolCalls, critique.reason());
            }
            if (critique.verdict() != Verdict.REPLAN || replans >= maxReplans(request)) {
                return terminal(request, Status.INSUFFICIENT_EVIDENCE, plan, audit, List.of(),
                        replans, toolCalls, critique.reason());
            }
            replans++;
        }
    }

    private List<Evidence> selectAccepted(List<StepResult> audit, List<String> acceptedIds) {
        Set<String> accepted = new LinkedHashSet<>(acceptedIds);
        List<Evidence> values = new ArrayList<>();
        for (Evidence value : EvidenceRankFusion.fuse(audit)) {
            if (accepted.contains(value.evidenceId()) && values.size() < maxAcceptedEvidence()) {
                values.add(value);
            }
        }
        return List.copyOf(values);
    }

    private Result terminal(Request request, Status status, Plan plan, List<StepResult> audit,
                            List<Evidence> accepted, int replans, int toolCalls, String reason) {
        List<Evidence> candidates = Result.distinctEvidence(audit);
        emit(request, new WorkflowEvent("WORKFLOW", plan == null ? 0 : plan.generation(), "workflow", null,
                status.name(), reason, 0, accepted.stream().map(Evidence::evidenceId).toList()));
        return new Result(status, plan, List.copyOf(audit), candidates, accepted,
                replans, toolCalls, reason);
    }

    private boolean expired(java.time.Instant deadline) {
        return !clock.now().isBefore(deadline);
    }

    private boolean isStageTimeout(RuntimeException failure) {
        return failure instanceof IllegalStateException
                && "WORKFLOW_DECISION_TIMEOUT".equals(failure.getMessage());
    }

    private int maxToolCalls(Request request) {
        return Math.min(request.maxToolCalls(), Math.max(1, properties.getMaxToolCalls()));
    }

    private int maxReplans(Request request) {
        return Math.min(request.maxReplans(), Math.max(0, properties.getMaxReplans()));
    }

    private int maxAcceptedEvidence() {
        return Math.min(6, Math.max(1, properties.getMaxAcceptedEvidence()));
    }

    private void emit(Request request, WorkflowEvent event) {
        try {
            request.observer().onEvent(event);
        } catch (RuntimeException observerFailure) {
            log.warn("Workflow observer failed, phase={}, stepId={}, failureType={}",
                    event.phase(), event.stepId(), observerFailure.getClass().getSimpleName());
        }
    }

    private String planMessage(Route route) {
        return switch (route) {
            case DIRECT_CONVERSATION -> "正在确认对话意图";
            case VIDEO_RAG -> "正在检索视频内容";
            case DOCUMENT_RAG -> "正在检索文档内容";
            case MIXED_RAG -> "正在检索视频与文档";
            case OUT_OF_SCOPE -> "正在确认知识范围";
        };
    }

    private String toolMessage(String tool) {
        return StructuredLlmAgentPlanner.VIDEO_TOOL.equals(tool)
                ? "正在检索视频" : "正在检索文档";
    }

    private enum Phase { PLANNER, EXECUTOR, CRITIC }

    private static final class StageBudgets {
        private final long overallDeadlineNanos;
        private long plannerRemainingNanos;
        private long executorRemainingNanos;
        private long criticRemainingNanos;

        private StageBudgets(java.time.Duration total, AgentWorkflowProperties properties) {
            long now = System.nanoTime();
            overallDeadlineNanos = saturatedAdd(now, total.toNanos());
            plannerRemainingNanos = TimeUnit.MILLISECONDS.toNanos(Math.min(30_000,
                    Math.max(1, properties.getPlannerTimeoutMillis())));
            executorRemainingNanos = TimeUnit.MILLISECONDS.toNanos(Math.min(40_000,
                    Math.max(1, properties.getExecutorTimeoutMillis())));
            criticRemainingNanos = TimeUnit.MILLISECONDS.toNanos(Math.min(30_000,
                    Math.max(1, properties.getCriticTimeoutMillis())));
        }

        private long timeoutMillis(Phase phase) {
            long remaining = Math.min(remainingNanos(phase), overallDeadlineNanos - System.nanoTime());
            if (remaining <= 0) return 0;
            return Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining));
        }

        private void consume(Phase phase, long elapsedNanos) {
            long remaining = Math.max(0, remainingNanos(phase) - Math.max(0, elapsedNanos));
            switch (phase) {
                case PLANNER -> plannerRemainingNanos = remaining;
                case EXECUTOR -> executorRemainingNanos = remaining;
                case CRITIC -> criticRemainingNanos = remaining;
            }
        }

        private long remainingNanos(Phase phase) {
            return switch (phase) {
                case PLANNER -> plannerRemainingNanos;
                case EXECUTOR -> executorRemainingNanos;
                case CRITIC -> criticRemainingNanos;
            };
        }

        private boolean overallExpired() {
            return System.nanoTime() >= overallDeadlineNanos;
        }

        private static long saturatedAdd(long left, long right) {
            try {
                return Math.addExact(left, right);
            } catch (ArithmeticException overflow) {
                return Long.MAX_VALUE;
            }
        }
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private static void validate(Request request) {
        if (request == null || request.userId() == null || request.question() == null
                || request.question().isBlank() || request.deadline().isNegative()
                || request.deadline().isZero()) {
            throw new IllegalArgumentException("invalid agent workflow request");
        }
    }
}
