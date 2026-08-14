package com.videomind.module.task.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.videomind.common.exception.BizException;
import com.videomind.module.task.entity.TaskCheckpoint;
import com.videomind.module.task.mapper.TaskCheckpointMapper;
import org.junit.jupiter.api.Test;

class TaskCheckpointServiceImplTest {
    private final TaskCheckpointMapper mapper = mock(TaskCheckpointMapper.class);
    private final TaskCheckpointServiceImpl service = new TaskCheckpointServiceImpl(mapper);

    @Test
    void repeatedCompletionWithSameChecksumIsIdempotent() {
        TaskCheckpoint existing = checkpoint("PARSED", "sha-a");
        when(mapper.selectOne(any())).thenReturn(existing);

        assertThat(service.complete(3L, "PARSED", "{}", "sha-a")).isSameAs(existing);
        verify(mapper, never()).insert(any(TaskCheckpoint.class));
    }

    @Test
    void repeatedCompletionWithDifferentChecksumIsRejected() {
        when(mapper.selectOne(any())).thenReturn(checkpoint("PARSED", "sha-a"));

        assertThatThrownBy(() -> service.complete(3L, "PARSED", "{}", "sha-b"))
                .isInstanceOfSatisfying(BizException.class, error -> assertThat(error.getCode()).isEqualTo(409));
    }

    @Test
    void firstCompletionPersistsImmutableStageArtifact() {
        when(mapper.selectOne(any())).thenReturn(null);
        when(mapper.insert(any(TaskCheckpoint.class))).thenReturn(1);

        TaskCheckpoint result = service.complete(3L, "EMBEDDED", "{\"count\":4}", "sha-c");

        assertThat(result.getStatus()).isEqualTo("COMPLETED");
        assertThat(result.getArtifactJson()).isEqualTo("{\"count\":4}");
        verify(mapper).insert(result);
    }

    private static TaskCheckpoint checkpoint(String stage, String checksum) {
        TaskCheckpoint value = new TaskCheckpoint();
        value.setId(1L);
        value.setTaskId(3L);
        value.setStage(stage);
        value.setStatus("COMPLETED");
        value.setChecksum(checksum);
        return value;
    }
}
