package com.videomind.module.agent.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.videomind.module.agent.workflow.AgentWorkflowModels.Request;
import com.videomind.module.agent.workflow.AgentWorkflowModels.Result;
import com.videomind.module.agent.workflow.WorkflowEvent;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@RequiredArgsConstructor
public class WorkflowAuditRepository {
    private final ChatGenerationMapper generations;
    private final AgentExecutionMapper executions;
    private final AgentStepMapper steps;
    private final ObjectMapper objectMapper;

    @Transactional(rollbackFor = Exception.class)
    public StartedAudit start(Long generationId, Request request) {
        LocalDateTime now = LocalDateTime.now();
        ChatGeneration generation = new ChatGeneration();
        generation.setId(generationId);
        generation.setConversationId(request.conversationId());
        generation.setUserId(request.userId());
        generation.setClientRequestId(UUID.randomUUID().toString());
        generation.setStatus("RUNNING");
        generation.setQuestion(request.question());
        generation.setStartedTime(now);
        generation.setCreatedTime(now);
        generation.setUpdatedTime(now);
        generations.insert(generation);
        AgentExecution execution = new AgentExecution();
        execution.setGenerationId(generationId);
        execution.setProfile(request.mode().name());
        execution.setState("RUNNING");
        execution.setToolCalls(0);
        execution.setReplanCount(0);
        execution.setTokenUsage(0L);
        execution.setDeadlineAt(now.plus(request.deadline()));
        execution.setCreatedTime(now);
        execution.setUpdatedTime(now);
        executions.insert(execution);
        if (execution.getId() == null) {
            throw new IllegalStateException("AGENT_EXECUTION_ID_MISSING");
        }
        return new StartedAudit(generationId, execution.getId());
    }

    public void record(Long executionId, int index, WorkflowEvent event) {
        LocalDateTime now = LocalDateTime.now();
        AgentStep step = new AgentStep();
        step.setExecutionId(executionId);
        step.setStepIndex(index);
        step.setNodeType(event.phase());
        step.setStatus(event.status());
        step.setInputJson(json(Map.of("planGeneration", event.planGeneration(),
                "stepId", event.stepId(), "tool", event.tool() == null ? "" : event.tool())));
        step.setOutputJson(json(Map.of("message", event.message(), "elapsedMs", event.elapsedMs(),
                "evidenceIds", event.evidenceIds())));
        step.setStartedTime(now);
        step.setFinishedTime("STARTED".equals(event.status()) ? null : now);
        step.setCreatedTime(now);
        steps.insert(step);
    }

    public void finishExecution(Long executionId, Result result) {
        AgentExecution execution = new AgentExecution();
        execution.setId(executionId);
        execution.setState(result.status().name());
        execution.setRoute(result.finalPlan() == null ? null : result.finalPlan().route());
        execution.setToolCalls(result.toolCalls());
        execution.setReplanCount(result.replans());
        execution.setUpdatedTime(LocalDateTime.now());
        executions.updateById(execution);
    }

    public void completeGeneration(Long generationId, String answer) {
        ChatGeneration generation = new ChatGeneration();
        generation.setId(generationId);
        generation.setStatus("SUCCESS");
        generation.setPartialAnswer(answer);
        generation.setFinishedTime(LocalDateTime.now());
        generation.setUpdatedTime(generation.getFinishedTime());
        generations.updateById(generation);
    }

    public void fail(Long generationId, Long executionId, Throwable failure) {
        LocalDateTime now = LocalDateTime.now();
        AgentExecution execution = new AgentExecution();
        execution.setId(executionId);
        execution.setState("FAILED");
        execution.setUpdatedTime(now);
        executions.updateById(execution);
        ChatGeneration generation = new ChatGeneration();
        generation.setId(generationId);
        generation.setStatus("FAILED");
        generation.setErrorCode(failure.getClass().getSimpleName());
        generation.setErrorMessage(shorten(failure.getMessage(), 2048));
        generation.setFinishedTime(now);
        generation.setUpdatedTime(now);
        generations.updateById(generation);
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("WORKFLOW_AUDIT_JSON_FAILED", failure);
        }
    }

    private static String shorten(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        return value.substring(0, max);
    }

    public record StartedAudit(Long generationId, Long executionId) {
    }
}
