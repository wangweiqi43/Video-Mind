package com.videomind.module.task.analysis.tencent;

import com.videomind.module.task.analysis.SpeechToTextClient;
import com.videomind.module.task.analysis.chunk.AsrChunkResultMerger;
import com.videomind.module.task.analysis.chunk.FfmpegAudioChunker;
import com.videomind.module.task.analysis.dto.AsrResult;
import com.videomind.module.task.analysis.dto.AudioExtractionResult;
import com.videomind.module.task.entity.TaskRecord;
import com.videomind.module.video.entity.VideoFile;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnExpression("'${videomind.ai.asr.mode:mock}' == 'real' && '${videomind.ai.asr.provider:generic}' == 'tencent'")
public class TencentTimestampSpeechToTextClient implements SpeechToTextClient {
    private final FfmpegAudioChunker chunker;
    private final TencentAsrChunkTranscriber transcriber;
    private final AsrChunkResultMerger merger;

    @Override
    public AsrResult transcribe(Long processingTaskId, AudioExtractionResult audio,
                                VideoFile videoFile, TaskRecord taskRecord) {
        long durationMs = Math.multiplyExact(requireDurationSeconds(audio), 1_000L);
        var artifacts = chunker.split(audio);
        var completed = transcriber.transcribe(processingTaskId, artifacts, taskRecord);
        return merger.merge(completed, durationMs);
    }

    private static long requireDurationSeconds(AudioExtractionResult audio) {
        if (audio == null || audio.getDurationSeconds() == null || audio.getDurationSeconds() <= 0) {
            throw new IllegalStateException("ASR_AUDIO_DURATION_MISSING");
        }
        return audio.getDurationSeconds();
    }
}
