package com.videomind.module.agent.workflow;

import com.videomind.module.agent.workflow.AgentWorkflowModels.Critique;
import com.videomind.module.agent.workflow.AgentWorkflowModels.Plan;
import com.videomind.module.agent.workflow.AgentWorkflowModels.Request;
import com.videomind.module.agent.workflow.AgentWorkflowModels.Result;
import com.videomind.module.agent.workflow.AgentWorkflowModels.Status;
import com.videomind.module.agent.workflow.AgentWorkflowModels.StepResult;
import com.videomind.module.agent.workflow.AgentWorkflowModels.Verdict;
import com.videomind.module.knowledge.retrieval.Evidence;
import java.util.ArrayList;
import java.util.List;
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

    public Result run(Request request) {
        validate(request);
        request.cancellation().check();
        java.time.Instant deadline = clock.now().plus(request.deadline());
        Plan plan = null;
        Critique critique = null;
        List<StepResult> audit = new ArrayList<>();
        int toolCalls = 0;
        int replans = 0;
        while (true) {
            request.cancellation().check();
            if (expired(deadline)) {
                return result(request, Status.DEADLINE_EXCEEDED, plan, audit, replans, toolCalls, "工作流超过 deadline");
            }
            plan = planner.plan(request, plan, critique, replans);
            request.cancellation().check();
            emit(request, new WorkflowEvent("PLAN", plan.generation(), "plan-" + plan.generation(), null,
                    "COMPLETED", "计划已生成：" + plan.route(), 0, List.of()));
            if (expired(deadline)) {
                return result(request, Status.DEADLINE_EXCEEDED, plan, audit, replans, toolCalls,
                        "工作流超过 deadline");
            }
            List<StepResult> round = new ArrayList<>();
            for (var step : plan.steps()) {
                request.cancellation().check();
                if (toolCalls >= request.maxToolCalls()) {
                    return result(request, Status.TOOL_BUDGET_EXCEEDED, plan, audit, replans, toolCalls,
                            "工具调用次数超过预算");
                }
                if (expired(deadline)) {
                    return result(request, Status.DEADLINE_EXCEEDED, plan, audit, replans, toolCalls,
                            "工作流超过 deadline");
                }
                long stepStart = System.nanoTime();
                emit(request, new WorkflowEvent("TOOL", plan.generation(), step.id(), step.tool(),
                        "STARTED", "工具调用开始", 0, List.of()));
                StepResult value;
                try {
                    value = executor.execute(request, step);
                    request.cancellation().check();
                } catch (RuntimeException failure) {
                    emit(request, new WorkflowEvent("TOOL", plan.generation(), step.id(), step.tool(),
                            "FAILED", "工具调用失败", elapsedMs(stepStart), List.of()));
                    throw failure;
                }
                round.add(value);
                audit.add(value);
                toolCalls++;
                emit(request, new WorkflowEvent("TOOL", plan.generation(), step.id(), step.tool(),
                        "COMPLETED", "工具调用完成", elapsedMs(stepStart), value.evidence().stream()
                        .map(com.videomind.module.knowledge.retrieval.Evidence::evidenceId).toList()));
                if (expired(deadline)) {
                    return result(request, Status.DEADLINE_EXCEEDED, plan, audit, replans, toolCalls,
                            "工作流超过 deadline");
                }
            }
            critique = critic.review(request, plan, round, replans);
            request.cancellation().check();
            emit(request, new WorkflowEvent("CRITIC", plan.generation(), "critic-" + plan.generation(), null,
                    critique.verdict().name(), critique.reason(), 0, round.stream()
                    .flatMap(value -> value.evidence().stream())
                    .map(com.videomind.module.knowledge.retrieval.Evidence::evidenceId).distinct().toList()));
            if (expired(deadline)) {
                return result(request, Status.DEADLINE_EXCEEDED, plan, audit, replans, toolCalls,
                        "工作流超过 deadline");
            }
            if (critique.verdict() == Verdict.ACCEPT) {
                return result(request, Status.COMPLETED, plan, audit, replans, toolCalls, critique.reason());
            }
            if (critique.verdict() != Verdict.REPLAN || replans >= request.maxReplans()) {
                return result(request, Status.INSUFFICIENT_EVIDENCE, plan, audit, replans, toolCalls, critique.reason());
            }
            replans++;
        }
    }

    private boolean expired(java.time.Instant deadline) {
        return !clock.now().isBefore(deadline);
    }

    private Result result(Request request, Status status, Plan plan, List<StepResult> audit, int replans,
                          int toolCalls, String reason) {
        List<Evidence> evidence = audit.stream().flatMap(value -> value.evidence().stream()).distinct().toList();
        emit(request, new WorkflowEvent("WORKFLOW", plan == null ? 0 : plan.generation(), "workflow", null,
                status.name(), reason, 0, evidence.stream().map(Evidence::evidenceId).toList()));
        return new Result(status, plan, List.copyOf(audit), evidence, replans, toolCalls, reason);
    }

    private void emit(Request request, WorkflowEvent event) {
        try {
            request.observer().onEvent(event);
        } catch (RuntimeException observerFailure) {
            log.warn("Workflow observer failed, phase={}, stepId={}, failureType={}",
                    event.phase(), event.stepId(), observerFailure.getClass().getSimpleName());
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
