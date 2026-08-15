package com.videomind.module.task.analysis.chunk;

import com.videomind.common.exception.BizException;
import com.videomind.config.FfmpegProperties;
import com.videomind.config.TencentAsrProperties;
import com.videomind.module.task.analysis.dto.AudioExtractionResult;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class FfmpegAudioChunker {
    private static final Duration CHUNK_TIMEOUT = Duration.ofMinutes(2);

    private final AudioChunkPlanner planner;
    private final FfmpegProperties ffmpeg;
    private final TencentAsrProperties asr;

    public List<AudioChunkArtifact> split(AudioExtractionResult audio) {
        if (audio == null || audio.getDurationSeconds() == null || audio.getDurationSeconds() <= 0) {
            throw new BizException(500, "ASR 音频缺少有效时长");
        }
        Path source = Path.of(audio.getAudioPath()).toAbsolutePath().normalize();
        if (!Files.isRegularFile(source)) {
            throw new BizException(500, "ASR 音频文件不存在：" + source);
        }
        try {
            Path outputDir = source.getParent().resolve("asr-chunks").normalize();
            Files.createDirectories(outputDir);
            List<AudioChunkArtifact> result = new ArrayList<>();
            for (AudioChunkPlan plan : planner.plan(audio.getDurationSeconds() * 1_000L)) {
                result.add(createChunk(source, outputDir, plan));
            }
            return List.copyOf(result);
        } catch (BizException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new BizException(500, "FFmpeg 切分 ASR 音频失败：" + failure.getMessage());
        }
    }

    private AudioChunkArtifact createChunk(Path source, Path outputDir, AudioChunkPlan plan) throws Exception {
        Path output = outputDir.resolve("chunk-%05d.wav".formatted(plan.chunkIndex()));
        Path log = outputDir.resolve("chunk-%05d.log".formatted(plan.chunkIndex()));
        List<String> command = List.of(
                ffmpeg.getBinaryPath(), "-y",
                "-ss", seconds(plan.extractionStartMs()),
                "-i", source.toString(),
                "-t", seconds(plan.extractionDurationMs()),
                "-vn", "-map_metadata", "-1",
                "-acodec", "pcm_s16le", "-ar", "16000", "-ac", "1",
                "-fflags", "+bitexact", "-flags:a", "+bitexact",
                output.toString());
        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(log.toFile())
                .start();
        if (!process.waitFor(CHUNK_TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new BizException(504, "FFmpeg ASR 分片超时，chunk=" + plan.chunkIndex());
        }
        if (process.exitValue() != 0 || !Files.isRegularFile(output)) {
            throw new BizException(500, "FFmpeg ASR 分片失败，日志：" + log);
        }
        long size = Files.size(output);
        if (size <= 44) {
            throw new BizException(500, "FFmpeg 生成了空 ASR 分片，chunk=" + plan.chunkIndex());
        }
        if (size > asr.getMaxInlineAudioBytes()) {
            throw new BizException(500, "ASR 分片超过本地上传限制，chunk=" + plan.chunkIndex()
                    + "，bytes=" + size + "，limit=" + asr.getMaxInlineAudioBytes());
        }
        return new AudioChunkArtifact(plan, output, sha256(output), size);
    }

    private static String seconds(long millis) {
        return String.format(Locale.ROOT, "%.3f", millis / 1_000d);
    }

    private static String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[8192];
            for (int read; (read = input.read(buffer)) >= 0;) {
                if (read > 0) digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
