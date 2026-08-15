package com.videomind.module.task.analysis.tencent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.videomind.common.exception.BizException;
import com.videomind.config.TencentAsrProperties;
import com.videomind.module.task.analysis.chunk.AudioChunkArtifact;
import com.videomind.module.task.analysis.chunk.AudioChunkPlan;
import com.videomind.module.task.analysis.chunk.VideoAsrChunk;
import com.videomind.module.task.analysis.chunk.VideoAsrChunkState;
import com.videomind.module.task.analysis.chunk.VideoAsrChunkStore;
import com.videomind.module.task.entity.TaskRecord;
import com.videomind.module.task.service.TaskCancellationException;
import com.videomind.module.task.service.TaskCancellationGuard;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

class TencentAsrChunkTranscriberTest {
    @TempDir
    Path tempDir;

    private final TencentAsrApiTransport transport = mock(TencentAsrApiTransport.class);
    private final VideoAsrChunkStore store = mock(VideoAsrChunkStore.class);
    private final TaskCancellationGuard cancellation = mock(TaskCancellationGuard.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private TencentAsrProperties properties;
    private TencentAsrChunkTranscriber transcriber;

    @BeforeEach
    void setUp() {
        properties = new TencentAsrProperties();
        properties.setSecretId("id");
        properties.setSecretKey("key");
        properties.setPollIntervalMillis(1);
        properties.setTimeoutSeconds(2);
        transcriber = new TencentAsrChunkTranscriber(properties, transport,
                new TencentAsrResponseParser(objectMapper), store, cancellation, objectMapper);
    }

    @Test
    void submitsInlinePersistsProviderIdAndCompletesChunk() throws Exception {
        AudioChunkArtifact artifact = artifact();
        VideoAsrChunk planned = chunk(VideoAsrChunkState.PLANNED);
        VideoAsrChunk submitting = chunk(VideoAsrChunkState.SUBMITTING);
        submitting.setSubmitAttempt(1);
        VideoAsrChunk submitted = chunk(VideoAsrChunkState.SUBMITTED);
        submitted.setSubmitAttempt(1);
        submitted.setProviderTaskId("1001");
        submitted.setSubmittedTime(LocalDateTime.now());
        when(store.ensurePlans(eq(99L), any(), eq(List.of(artifact)), anyString()))
                .thenReturn(List.of(planned));
        when(store.claimSubmission(1L)).thenReturn(submitting);
        when(store.markSubmitted(1L, 1, "1001")).thenReturn(submitted);
        when(transport.post(eq("CreateRecTask"), anyString())).thenReturn(createResponse());
        when(transport.post(eq("DescribeTaskStatus"), anyString())).thenReturn(successResponse());

        var result = transcriber.transcribe(99L, List.of(artifact), task());

        assertThat(result).hasSize(1);
        assertThat(result.get(0).result().text()).isEqualTo("第一句");
        assertThat(Files.exists(artifact.path())).isFalse();
        verify(store).markSucceeded(eq(submitted), any(TencentAsrTaskResult.class));
        verify(cancellation, atLeastOnce()).checkProcessingTask(99L);
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(transport).post(eq("CreateRecTask"), payload.capture());
        assertThat(objectMapper.readTree(payload.getValue()).path("SourceType").asInt()).isEqualTo(1);
        assertThat(objectMapper.readTree(payload.getValue()).path("DataLen").asInt()).isEqualTo(3);
    }

    @Test
    void resumesPollingPersistedProviderTaskWithoutResubmission() throws Exception {
        AudioChunkArtifact artifact = artifact();
        VideoAsrChunk submitted = chunk(VideoAsrChunkState.SUBMITTED);
        submitted.setProviderTaskId("1001");
        submitted.setSubmittedTime(LocalDateTime.now());
        when(store.ensurePlans(eq(99L), any(), any(), anyString())).thenReturn(List.of(submitted));
        when(transport.post(eq("DescribeTaskStatus"), anyString())).thenReturn(successResponse());

        transcriber.transcribe(99L, List.of(artifact), task());

        verify(transport, never()).post(eq("CreateRecTask"), anyString());
        verify(store).markSucceeded(eq(submitted), any(TencentAsrTaskResult.class));
    }

    @Test
    void reusesPersistedSuccessfulResultWithoutCloudCall() throws Exception {
        AudioChunkArtifact artifact = artifact();
        VideoAsrChunk succeeded = chunk(VideoAsrChunkState.SUCCEEDED);
        TencentAsrTaskResult result = successfulResult();
        when(store.ensurePlans(eq(99L), any(), any(), anyString())).thenReturn(List.of(succeeded));
        when(store.loadResult(succeeded)).thenReturn(result);

        assertThat(transcriber.transcribe(99L, List.of(artifact), task()).get(0).result())
                .isEqualTo(result);
        verifyNoInteractions(transport);
    }

    @Test
    void leavesAmbiguousNetworkSubmissionInSubmittingState() throws Exception {
        AudioChunkArtifact artifact = artifact();
        VideoAsrChunk planned = chunk(VideoAsrChunkState.PLANNED);
        VideoAsrChunk submitting = chunk(VideoAsrChunkState.SUBMITTING);
        submitting.setSubmitAttempt(1);
        when(store.ensurePlans(eq(99L), any(), any(), anyString())).thenReturn(List.of(planned));
        when(store.claimSubmission(1L)).thenReturn(submitting);
        when(transport.post(eq("CreateRecTask"), anyString()))
                .thenThrow(new BizException(502, "network outcome unknown"));

        assertThatThrownBy(() -> transcriber.transcribe(99L, List.of(artifact), task()))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("network outcome unknown");
        verify(store, never()).markFailed(eq(1L), anyString(), anyString());
    }

    @Test
    void marksExplicitProviderFailureForNextTaskRetry() throws Exception {
        AudioChunkArtifact artifact = artifact();
        VideoAsrChunk submitted = chunk(VideoAsrChunkState.SUBMITTED);
        submitted.setProviderTaskId("1001");
        submitted.setSubmittedTime(LocalDateTime.now());
        when(store.ensurePlans(eq(99L), any(), any(), anyString())).thenReturn(List.of(submitted));
        when(transport.post(eq("DescribeTaskStatus"), anyString())).thenReturn(failedResponse());

        assertThatThrownBy(() -> transcriber.transcribe(99L, List.of(artifact), task()))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("provider failed");
        verify(store).markFailed(1L, "PROVIDER_FAILED", "provider failed");
    }

    @Test
    void expiresOldProviderTaskThenResubmits() throws Exception {
        AudioChunkArtifact artifact = artifact();
        VideoAsrChunk expired = chunk(VideoAsrChunkState.SUBMITTED);
        expired.setProviderTaskId("900");
        expired.setSubmittedTime(LocalDateTime.now().minusHours(25));
        VideoAsrChunk failed = chunk(VideoAsrChunkState.FAILED);
        VideoAsrChunk submitting = chunk(VideoAsrChunkState.SUBMITTING);
        submitting.setSubmitAttempt(2);
        VideoAsrChunk submitted = chunk(VideoAsrChunkState.SUBMITTED);
        submitted.setSubmitAttempt(2);
        submitted.setProviderTaskId("1001");
        submitted.setSubmittedTime(LocalDateTime.now());
        when(store.ensurePlans(eq(99L), any(), any(), anyString())).thenReturn(List.of(expired));
        when(store.expireSubmitted(eq(expired), any())).thenReturn(failed);
        when(store.claimSubmission(1L)).thenReturn(submitting);
        when(store.markSubmitted(1L, 2, "1001")).thenReturn(submitted);
        when(transport.post(eq("CreateRecTask"), anyString())).thenReturn(createResponse());
        when(transport.post(eq("DescribeTaskStatus"), anyString())).thenReturn(successResponse());

        transcriber.transcribe(99L, List.of(artifact), task());

        verify(store).expireSubmitted(eq(expired), any());
        verify(transport).post(eq("CreateRecTask"), anyString());
    }

    @Test
    void leavesSubmittedTaskForRetryWhenPollingDeadlineExpires() throws Exception {
        properties.setTimeoutSeconds(0);
        AudioChunkArtifact artifact = artifact();
        VideoAsrChunk submitted = chunk(VideoAsrChunkState.SUBMITTED);
        submitted.setProviderTaskId("1001");
        submitted.setSubmittedTime(LocalDateTime.now());
        when(store.ensurePlans(eq(99L), any(), any(), anyString())).thenReturn(List.of(submitted));

        assertThatThrownBy(() -> transcriber.transcribe(99L, List.of(artifact), task()))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("轮询超时");
        verifyNoInteractions(transport);
        verify(store, never()).markFailed(eq(1L), anyString(), anyString());
    }

    @Test
    void stopsBeforeCloudCallWhenCancellationIsRequested() throws Exception {
        AudioChunkArtifact artifact = artifact();
        when(store.ensurePlans(eq(99L), any(), any(), anyString()))
                .thenReturn(List.of(chunk(VideoAsrChunkState.PLANNED)));
        org.mockito.Mockito.doThrow(new TaskCancellationException())
                .when(cancellation).checkProcessingTask(99L);

        assertThatThrownBy(() -> transcriber.transcribe(99L, List.of(artifact), task()))
                .isInstanceOf(TaskCancellationException.class);
        verifyNoInteractions(transport);
    }

    private AudioChunkArtifact artifact() throws Exception {
        Path path = tempDir.resolve("chunk-00000.wav");
        Files.write(path, new byte[]{1, 2, 3});
        return new AudioChunkArtifact(new AudioChunkPlan(0, 0, 40_000, 0, 40_000),
                path, "a".repeat(64), 3);
    }

    private static TaskRecord task() {
        TaskRecord task = new TaskRecord();
        task.setId(9L);
        task.setVideoId(7L);
        task.setUserId(5L);
        return task;
    }

    private static VideoAsrChunk chunk(VideoAsrChunkState state) {
        VideoAsrChunk chunk = new VideoAsrChunk();
        chunk.setId(1L);
        chunk.setProcessingTaskId(99L);
        chunk.setTaskRecordId(9L);
        chunk.setVideoId(7L);
        chunk.setUserId(5L);
        chunk.setChunkIndex(0);
        chunk.setState(state);
        chunk.setSubmitAttempt(0);
        chunk.setUpdatedTime(LocalDateTime.now());
        return chunk;
    }

    private static TencentAsrTaskResult successfulResult() {
        return new TencentAsrTaskResult(1001, TencentAsrTaskResult.Status.SUCCEEDED,
                "第一句", List.of(), "", "poll");
    }

    private static String createResponse() {
        return "{\"Response\":{\"Data\":{\"TaskId\":1001},\"RequestId\":\"create\"}}";
    }

    private static String successResponse() {
        return """
                {"Response":{"RequestId":"poll","Data":{"TaskId":1001,"Status":2,
                "Result":"第一句","ErrorMsg":"","ResultDetail":[
                {"FinalSentence":"第一句","StartMs":100,"EndMs":900,"SpeakerId":0}]}}}
                """;
    }

    private static String failedResponse() {
        return """
                {"Response":{"RequestId":"poll","Data":{"TaskId":1001,"Status":3,
                "Result":"","ErrorMsg":"provider failed","ResultDetail":[]}}}
                """;
    }
}
