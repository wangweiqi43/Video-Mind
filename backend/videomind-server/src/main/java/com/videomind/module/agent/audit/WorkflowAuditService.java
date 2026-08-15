package com.videomind.module.agent.audit;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.videomind.module.agent.workflow.AgentWorkflowModels.Request;
import com.videomind.module.agent.workflow.AgentWorkflowModels.Result;
import com.videomind.module.agent.workflow.WorkflowEvent;
import com.videomind.module.agent.workflow.WorkflowObserver;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class WorkflowAuditService {
    private final WorkflowAuditRepository repository;

    public Session start(Request request, WorkflowObserver downstream) {
        long generationId = IdWorker.getId();
        WorkflowAuditRepository.StartedAudit started = repository.start(generationId, request);
        return new Session(started.generationId(), started.executionId(), repository,
                downstream == null ? WorkflowObserver.NOOP : downstream);
    }

    public static final class Session implements WorkflowObserver {
        private final Long generationId;
        private final Long executionId;
        private final WorkflowAuditRepository repository;
        private final WorkflowObserver downstream;
        private final AtomicInteger eventIndex = new AtomicInteger();

        private Session(Long generationId, Long executionId, WorkflowAuditRepository repository,
                        WorkflowObserver downstream) {
            this.generationId = generationId;
            this.executionId = executionId;
            this.repository = repository;
            this.downstream = downstream;
        }

        @Override
        public void onEvent(WorkflowEvent event) {
            int index = eventIndex.getAndIncrement();
            try {
                repository.record(executionId, index, event);
            } catch (RuntimeException auditFailure) {
                log.warn("Workflow audit event persistence failed: executionId={}, index={}, phase={}",
                        executionId, index, event.phase(), auditFailure);
            }
            downstream.onEvent(event);
        }

        public void workflowFinished(Result result) {
            repository.finishExecution(executionId, result);
        }

        public boolean answerCompleted(String answer) {
            return repository.completeGeneration(generationId, answer);
        }

        public void cancelled(String partialAnswer) {
            repository.cancel(generationId, executionId, partialAnswer);
        }

        public void failed(Throwable failure) {
            repository.fail(generationId, executionId, failure);
        }

        public Long generationId() {
            return generationId;
        }
    }
}
