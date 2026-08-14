package com.videomind.module.task.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.videomind.common.enums.ProcessingTaskType;
import com.videomind.module.task.entity.MqTransactionEvent;
import com.videomind.module.task.entity.ProcessingTask;
import com.videomind.module.task.mapper.MqTransactionEventMapper;
import com.videomind.module.task.mapper.ProcessingTaskMapper;
import com.videomind.module.task.mq.TaskCreateCommand;
import com.videomind.module.task.mq.TaskTransactionContext;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LocalTaskTransactionServiceImplTest {
    private final ProcessingTaskMapper tasks = mock(ProcessingTaskMapper.class);
    private final MqTransactionEventMapper events = mock(MqTransactionEventMapper.class);
    private final LocalTaskTransactionServiceImpl service = new LocalTaskTransactionServiceImpl(
            tasks, events, new ObjectMapper());

    @Test
    void insertsTaskAndCommitEvidenceInOneLocalTransaction() {
        when(tasks.insertIgnoreActive(any(ProcessingTask.class))).thenReturn(1);
        TaskTransactionContext context = context(101L);

        var result = service.createOrReuse(context);

        assertThat(result.taskId()).isEqualTo(101L);
        assertThat(result.reused()).isFalse();
        verify(events).insert(any(MqTransactionEvent.class));
        assertThat(context.getResolvedTaskId()).isEqualTo(101L);
    }

    @Test
    void insertIgnoreRaceReusesWinnerButStillRecordsThisEvent() {
        when(tasks.insertIgnoreActive(any(ProcessingTask.class))).thenReturn(0);
        ProcessingTask winner = new ProcessingTask();
        winner.setId(88L);
        when(tasks.selectOne(any())).thenReturn(winner);
        TaskTransactionContext context = context(102L);

        var result = service.createOrReuse(context);

        assertThat(result.taskId()).isEqualTo(88L);
        assertThat(result.reused()).isTrue();
        verify(events).insert(any(MqTransactionEvent.class));
    }

    private static TaskTransactionContext context(Long taskId) {
        TaskCreateCommand command = new TaskCreateCommand(7L, ProcessingTaskType.DOCUMENT_INGEST,
                31L, "DOCUMENT_INGEST:31:v1", "PARSE", 5, Map.of("versionId", 32L));
        return new TaskTransactionContext("event-1", taskId, "topic", "DOCUMENT_INGEST", command);
    }
}
