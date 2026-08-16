package com.videomind.module.agent.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.videomind.module.agent.workflow.AgentWorkflowModels;
import com.videomind.module.agent.workflow.WorkflowEvent;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class WorkflowAuditServiceTest {
    @Test
    void stillForwardsSseFeedbackWhenOneAuditStepWriteFails() {
        WorkflowAuditRepository repository = mock(WorkflowAuditRepository.class);
        when(repository.start(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new WorkflowAuditRepository.StartedAudit(61L, 71L));
        WorkflowEvent event = new WorkflowEvent("PLAN", 0, "plan-0", null,
                "COMPLETED", "计划已生成", 0L, List.of());
        doThrow(new IllegalStateException("db unavailable")).when(repository).record(71L, 0, event);
        List<WorkflowEvent> forwarded = new ArrayList<>();
        WorkflowAuditService service = new WorkflowAuditService(repository);
        var request = new AgentWorkflowModels.Request(9L, 13L, List.of(10L), "q");

        WorkflowAuditService.Session session = service.start(request, forwarded::add);
        session.onEvent(event);

        assertThat(forwarded).containsExactly(event);
    }
}
