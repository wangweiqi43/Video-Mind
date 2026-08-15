package com.videomind.module.task.analysis.chunk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.videomind.common.exception.BizException;
import com.videomind.config.FfmpegProperties;
import com.videomind.config.TencentAsrProperties;
import com.videomind.module.task.analysis.dto.AudioExtractionResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FfmpegAudioChunkerTest {
    @TempDir
    Path tempDir;

    @Test
    void producesDeterministicBoundedPcmChunks() throws Exception {
        String binary = ffmpegBinary();
        Assumptions.assumeTrue(available(binary));
        Path source = generateFiveSecondAudio(binary);
        TencentAsrProperties asr = properties(2, 250, 1024 * 1024);
        FfmpegAudioChunker chunker = chunker(binary, asr);
        AudioExtractionResult audio = AudioExtractionResult.builder()
                .audioPath(source.toString()).durationSeconds(250).audioDurationSeconds(5).build();

        List<AudioChunkArtifact> first = chunker.split(audio);
        List<AudioChunkArtifact> second = chunker.split(audio);

        assertThat(first).hasSize(3);
        assertThat(first).allSatisfy(chunk -> {
            assertThat(chunk.sizeBytes()).isBetween(45L, (long) asr.getMaxInlineAudioBytes());
            assertThat(chunk.sha256()).hasSize(64);
            assertThat(Files.isRegularFile(chunk.path())).isTrue();
        });
        assertThat(second.stream().map(AudioChunkArtifact::sha256))
                .containsExactlyElementsOf(first.stream().map(AudioChunkArtifact::sha256).toList());
    }

    @Test
    void rejectsChunkThatExceedsConfiguredInlineLimit() throws Exception {
        String binary = ffmpegBinary();
        Assumptions.assumeTrue(available(binary));
        Path source = generateFiveSecondAudio(binary);
        FfmpegAudioChunker chunker = chunker(binary, properties(2, 250, 100));

        assertThatThrownBy(() -> chunker.split(AudioExtractionResult.builder()
                .audioPath(source.toString()).durationSeconds(5).build()))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("超过本地上传限制");
    }

    private FfmpegAudioChunker chunker(String binary, TencentAsrProperties asr) {
        FfmpegProperties ffmpeg = new FfmpegProperties();
        ffmpeg.setBinaryPath(binary);
        return new FfmpegAudioChunker(new AudioChunkPlanner(asr), ffmpeg, asr);
    }

    private Path generateFiveSecondAudio(String binary) throws Exception {
        Path source = tempDir.resolve("audio.wav");
        Process process = new ProcessBuilder(binary, "-y", "-f", "lavfi", "-i",
                "sine=frequency=440:duration=5", "-ar", "16000", "-ac", "1", source.toString())
                .redirectErrorStream(true).redirectOutput(tempDir.resolve("generate.log").toFile()).start();
        assertThat(process.waitFor(30, TimeUnit.SECONDS)).isTrue();
        assertThat(process.exitValue()).isZero();
        return source;
    }

    private static TencentAsrProperties properties(int seconds, int overlapMillis, long maxBytes) {
        TencentAsrProperties properties = new TencentAsrProperties();
        properties.setChunkSeconds(seconds);
        properties.setChunkOverlapMillis(overlapMillis);
        properties.setMaxInlineAudioBytes(maxBytes);
        return properties;
    }

    private static String ffmpegBinary() {
        String configured = System.getenv("FFMPEG_BINARY_PATH");
        return configured == null || configured.isBlank() ? "ffmpeg" : configured;
    }

    private static boolean available(String binary) {
        try {
            Process process = new ProcessBuilder(binary, "-version").redirectErrorStream(true).start();
            return process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (Exception ignored) {
            return false;
        }
    }
}
