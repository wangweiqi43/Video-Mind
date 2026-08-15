package com.videomind.module.task.analysis.chunk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.videomind.module.task.entity.TaskRecord;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class VideoAsrChunkStoreTest {
    private final VideoAsrChunkMapper mapper = mock(VideoAsrChunkMapper.class);
    private final VideoAsrChunkStore store = new VideoAsrChunkStore(mapper, new ObjectMapper());

    @Test
    void persistsAndVerifiesDeterministicManifest() {
        TaskRecord task = task();
        AudioChunkArtifact artifact = artifact("a".repeat(64));
        VideoAsrChunk persisted = persisted(artifact.sha256(), "e".repeat(64));
        when(mapper.selectByProcessingTaskId(99L)).thenReturn(List.of(persisted));

        assertThat(store.ensurePlans(99L, task, List.of(artifact), "e".repeat(64)))
                .containsExactly(persisted);

        ArgumentCaptor<VideoAsrChunk> plan = ArgumentCaptor.forClass(VideoAsrChunk.class);
        verify(mapper).upsertPlan(plan.capture());
        assertThat(plan.getValue().getProcessingTaskId()).isEqualTo(99L);
        assertThat(plan.getValue().getState()).isEqualTo(VideoAsrChunkState.PLANNED);
        verify(mapper).bindAudioChecksum(eq(99L), eq(0), eq(artifact.sha256()), any(LocalDateTime.class));
    }

    @Test
    void rejectsChecksumOrEngineDriftOnResume() {
        when(mapper.selectByProcessingTaskId(99L)).thenReturn(
                List.of(persisted("b".repeat(64), "e".repeat(64))));

        assertThatThrownBy(() -> store.ensurePlans(99L, task(),
                List.of(artifact("a".repeat(64))), "e".repeat(64)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("ASR_CHUNK_MANIFEST_CHANGED");
    }

    private static TaskRecord task() {
        TaskRecord task = new TaskRecord();
        task.setId(9L);
        task.setVideoId(7L);
        task.setUserId(5L);
        return task;
    }

    private static AudioChunkArtifact artifact(String checksum) {
        return new AudioChunkArtifact(new AudioChunkPlan(0, 0, 40_000, 0, 40_000),
                Path.of("chunk.wav"), checksum, 3);
    }

    private static VideoAsrChunk persisted(String checksum, String signature) {
        VideoAsrChunk chunk = new VideoAsrChunk();
        chunk.setId(1L);
        chunk.setProcessingTaskId(99L);
        chunk.setTaskRecordId(9L);
        chunk.setVideoId(7L);
        chunk.setUserId(5L);
        chunk.setChunkIndex(0);
        chunk.setExtractionStartMs(0L);
        chunk.setExtractionEndMs(40_000L);
        chunk.setLogicalStartMs(0L);
        chunk.setLogicalEndMs(40_000L);
        chunk.setAudioSha256(checksum);
        chunk.setEngineSignature(signature);
        chunk.setState(VideoAsrChunkState.PLANNED);
        chunk.setSubmitAttempt(0);
        return chunk;
    }
}
