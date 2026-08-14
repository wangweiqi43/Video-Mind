package com.videomind.module.agent.workflow;

import com.videomind.module.agent.workflow.AgentWorkflowModels.Critique;
import com.videomind.module.agent.workflow.AgentWorkflowModels.Plan;
import com.videomind.module.agent.workflow.AgentWorkflowModels.Request;
import com.videomind.module.agent.workflow.AgentWorkflowModels.Result;
import com.videomind.module.agent.workflow.AgentWorkflowModels.Status;
import com.videomind.module.agent.workflow.AgentWorkflowModels.StepResult;
import com.videomind.module.agent.workflow.AgentWorkflowModels.Verdict;
import com.videomind.module.knowledge.retrieval.Evidence;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PlannerExecutorCriticWorkflow {
    static final int MAX_REPLANS = 1;
    private final AgentPlanner planner;
    private final AgentExecutor executor;
    private final AgentCritic critic;

    public Result run(Request request) {
        validate(request);
        Instant deadline = Instant.now().plus(request.deadline());
        Plan plan = null;
        Critique critique = null;
        List<StepResult> audit = new ArrayList<>();
        int toolCalls = 0;
        int replans = 0;
        while (true) {
            if (Instant.now().isAfter(deadline)) {
                return result(Status.DEADLINE_EXCEEDED, plan, audit, replans, toolCalls, "工作流超过 deadline");
            }
            plan = planner.plan(request, plan, critique, replans);
            List<StepResult> round = new ArrayList<>();
            for (var step : plan.steps()) {
                if (toolCalls >= request.maxToolCalls()) {
                    return result(Status.TOOL_BUDGET_EXCEEDED, plan, audit, replans, toolCalls,
                            "工具调用次数超过预算");
                }
                if (Instant.now().isAfter(deadline)) {
                    return result(Status.DEADLINE_EXCEEDED, plan, audit, replans, toolCalls,
                            "工作流超过 deadline");
                }
                StepResult value = executor.execute(request, step);
                round.add(value);
                audit.add(value);
                toolCalls++;
            }
            critique = critic.review(request, plan, round, replans);
            if (critique.verdict() == Verdict.ACCEPT) {
                return result(Status.COMPLETED, plan, audit, replans, toolCalls, critique.reason());
            }
            if (critique.verdict() != Verdict.REPLAN || replans >= MAX_REPLANS) {
                return result(Status.INSUFFICIENT_EVIDENCE, plan, audit, replans, toolCalls, critique.reason());
            }
            replans++;
        }
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
