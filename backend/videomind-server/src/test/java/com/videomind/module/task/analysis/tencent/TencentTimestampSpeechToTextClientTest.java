package com.videomind.module.task.analysis.tencent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.videomind.module.task.analysis.chunk.AsrChunkResultMerger;
import com.videomind.module.task.analysis.chunk.AudioChunkArtifact;
import com.videomind.module.task.analysis.chunk.AudioChunkPlan;
import com.videomind.module.task.analysis.chunk.CompletedAsrChunk;
import com.videomind.module.task.analysis.chunk.FfmpegAudioChunker;
import com.videomind.module.task.analysis.dto.AsrResult;
import com.videomind.module.task.analysis.dto.AudioExtractionResult;
import com.videomind.module.task.entity.TaskRecord;
import com.videomind.module.video.entity.VideoFile;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class TencentTimestampSpeechToTextClientTest {
    private final FfmpegAudioChunker chunker = mock(FfmpegAudioChunker.class);
    private final TencentAsrChunkTranscriber transcriber = mock(TencentAsrChunkTranscriber.class);
    private final AsrChunkResultMerger merger = mock(AsrChunkResultMerger.class);
    private final TencentTimestampSpeechToTextClient client =
            new TencentTimestampSpeechToTextClient(chunker, transcriber, merger);

    @Test
    void delegatesEveryTencentAudioToDurableChunkPipeline() {
        AudioExtractionResult audio = AudioExtractionResult.builder()
                .audioPath("audio.wav").durationSeconds(250).build();
        TaskRecord task = new TaskRecord();
        AudioChunkArtifact artifact = new AudioChunkArtifact(
                new AudioChunkPlan(0, 0, 121_000, 0, 120_000),
                Path.of("chunk.wav"), "a".repeat(64), 3_900_000);
        CompletedAsrChunk completed = new CompletedAsrChunk(artifact.plan(),
                new TencentAsrTaskResult(1001, TencentAsrTaskResult.Status.SUCCEEDED,
                        "第一句", List.of(), "", "request"));
        AsrResult expected = AsrResult.builder().language("zh-CN").text("第一句").build();
        when(chunker.split(audio)).thenReturn(List.of(artifact));
        when(transcriber.transcribe(99L, List.of(artifact), task)).thenReturn(List.of(completed));
        when(merger.merge(List.of(completed), 250_000L)).thenReturn(expected);

        assertThat(client.transcribe(99L, audio, new VideoFile(), task)).isSameAs(expected);

        verify(chunker).split(audio);
        verify(transcriber).transcribe(99L, List.of(artifact), task);
        verify(merger).merge(List.of(completed), 250_000L);
    }

    @Test
    void rejectsAudioWithoutDurationBeforeCreatingCloudTasks() {
        AudioExtractionResult audio = AudioExtractionResult.builder().audioPath("audio.wav").build();

        assertThatThrownBy(() -> client.transcribe(99L, audio, new VideoFile(), new TaskRecord()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("ASR_AUDIO_DURATION_MISSING");
    }
}
