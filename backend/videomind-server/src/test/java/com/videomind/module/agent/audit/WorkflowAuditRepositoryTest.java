package com.videomind.module.agent.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.videomind.module.agent.workflow.AgentWorkflowModels;
import com.videomind.module.agent.workflow.WorkflowEvent;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class WorkflowAuditRepositoryTest {
    private final ChatGenerationMapper generations = mock(ChatGenerationMapper.class);
    private final AgentExecutionMapper executions = mock(AgentExecutionMapper.class);
    private final AgentStepMapper steps = mock(AgentStepMapper.class);
    private final WorkflowAuditRepository repository = new WorkflowAuditRepository(
            generations, executions, steps, new ObjectMapper());

    @Test
    void persistsStructuredAuditWithoutHiddenReasoning() {
        doAnswer(invocation -> {
            invocation.<AgentExecution>getArgument(0).setId(71L);
            return 1;
        }).when(executions).insert(any(AgentExecution.class));
        var request = new AgentWorkflowModels.Request(9L, 13L, List.of(10L, 20L),
                "如何保证消费幂等");

        WorkflowAuditRepository.StartedAudit started = repository.start(61L, request);
        repository.record(started.executionId(), 0, new WorkflowEvent("TOOL", 2, "s1",
                "VIDEO_TIMELINE_RETRIEVAL", "COMPLETED", "工具调用完成", 37L, List.of("ev-1")));
        var result = new AgentWorkflowModels.Result(AgentWorkflowModels.Status.COMPLETED,
                new AgentWorkflowModels.Plan(AgentWorkflowModels.Route.VIDEO_RAG, List.of(), 1),
                List.of(), List.of(), 1, 2, "ok");
        repository.finishExecution(started.executionId(), result);
        repository.completeGeneration(started.generationId(), "答案");

        ArgumentCaptor<ChatGeneration> generation = ArgumentCaptor.forClass(ChatGeneration.class);
        verify(generations).insert(generation.capture());
        assertThat(generation.getValue().getConversationId()).isEqualTo(13L);
        assertThat(generation.getValue().getUserId()).isEqualTo(9L);
        assertThat(generation.getValue().getStatus()).isEqualTo("RUNNING");
        assertThat(generation.getValue().getQuestion()).isEqualTo("如何保证消费幂等");

        ArgumentCaptor<AgentStep> step = ArgumentCaptor.forClass(AgentStep.class);
        verify(steps).insert(step.capture());
        assertThat(step.getValue().getStepIndex()).isZero();
        assertThat(step.getValue().getInputJson()).contains("planGeneration", "stepId",
                "VIDEO_TIMELINE_RETRIEVAL");
        assertThat(step.getValue().getOutputJson()).contains("elapsedMs", "evidenceIds", "ev-1")
                .doesNotContain("reasoning", "thought", "chainOfThought");

        ArgumentCaptor<AgentExecution> executionUpdates = ArgumentCaptor.forClass(AgentExecution.class);
        verify(executions).updateById(executionUpdates.capture());
        assertThat(executionUpdates.getValue().getState()).isEqualTo("COMPLETED");
        assertThat(executionUpdates.getValue().getRoute()).isEqualTo("VIDEO_RAG");
        assertThat(executionUpdates.getValue().getToolCalls()).isEqualTo(2);

        ArgumentCaptor<AgentExecution> executionInsert = ArgumentCaptor.forClass(AgentExecution.class);
        verify(executions).insert(executionInsert.capture());
        assertThat(executionInsert.getValue().getProfile()).isEqualTo("PEC_BOUNDED");

        verify(generations).markSuccess(eq(61L), eq("答案"), any());
    }
}
