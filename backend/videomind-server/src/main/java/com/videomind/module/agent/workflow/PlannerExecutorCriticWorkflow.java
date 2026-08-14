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
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PlannerExecutorCriticWorkflow {
    private final AgentPlanner planner;
    private final AgentExecutor executor;
    private final AgentCritic critic;
    private final WorkflowClock clock;

    public Result run(Request request) {
        validate(request);
        java.time.Instant deadline = clock.now().plus(request.deadline());
        Plan plan = null;
        Critique critique = null;
        List<StepResult> audit = new ArrayList<>();
        int toolCalls = 0;
        int replans = 0;
        while (true) {
            if (expired(deadline)) {
                return result(Status.DEADLINE_EXCEEDED, plan, audit, replans, toolCalls, "工作流超过 deadline");
            }
            plan = planner.plan(request, plan, critique, replans);
            if (expired(deadline)) {
                return result(Status.DEADLINE_EXCEEDED, plan, audit, replans, toolCalls,
                        "工作流超过 deadline");
            }
            List<StepResult> round = new ArrayList<>();
            for (var step : plan.steps()) {
                if (toolCalls >= request.maxToolCalls()) {
                    return result(Status.TOOL_BUDGET_EXCEEDED, plan, audit, replans, toolCalls,
                            "工具调用次数超过预算");
                }
                if (expired(deadline)) {
                    return result(Status.DEADLINE_EXCEEDED, plan, audit, replans, toolCalls,
                            "工作流超过 deadline");
                }
                StepResult value = executor.execute(request, step);
                round.add(value);
                audit.add(value);
                toolCalls++;
                if (expired(deadline)) {
                    return result(Status.DEADLINE_EXCEEDED, plan, audit, replans, toolCalls,
                            "工作流超过 deadline");
                }
            }
            critique = critic.review(request, plan, round, replans);
            if (expired(deadline)) {
                return result(Status.DEADLINE_EXCEEDED, plan, audit, replans, toolCalls,
                        "工作流超过 deadline");
            }
            if (critique.verdict() == Verdict.ACCEPT) {
                return result(Status.COMPLETED, plan, audit, replans, toolCalls, critique.reason());
            }
            if (critique.verdict() != Verdict.REPLAN || replans >= request.maxReplans()) {
                return result(Status.INSUFFICIENT_EVIDENCE, plan, audit, replans, toolCalls, critique.reason());
            }
            replans++;
        }
    }

    private boolean expired(java.time.Instant deadline) {
        return !clock.now().isBefore(deadline);
    }

    private static Result result(Status status, Plan plan, List<StepResult> audit, int replans,
                                 int toolCalls, String reason) {
        List<Evidence> evidence = audit.stream().flatMap(value -> value.evidence().stream()).distinct().toList();
        return new Result(status, plan, List.copyOf(audit), evidence, replans, toolCalls, reason);
    }

    private static void validate(Request request) {
        if (request == null || request.userId() == null || request.question() == null
                || request.question().isBlank() || request.deadline().isNegative()
                || request.deadline().isZero()) {
            throw new IllegalArgumentException("invalid agent workflow request");
        }
    }
}
