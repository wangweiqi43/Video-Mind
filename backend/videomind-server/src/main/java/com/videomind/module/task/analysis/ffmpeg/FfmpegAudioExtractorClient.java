package com.videomind.module.task.analysis.ffmpeg;

import com.videomind.common.exception.BizException;
import com.videomind.config.FfmpegProperties;
import com.videomind.infrastructure.storage.ObjectStorageService;
import com.videomind.module.task.analysis.AudioExtractorClient;
import com.videomind.module.task.analysis.dto.AudioExtractionResult;
import com.videomind.module.task.entity.TaskRecord;
import com.videomind.module.video.entity.VideoFile;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "videomind.ffmpeg", name = "mode", havingValue = "ffmpeg", matchIfMissing = true)
public class FfmpegAudioExtractorClient implements AudioExtractorClient {

    private static final Duration FFMPEG_TIMEOUT = Duration.ofMinutes(10);

    private final ObjectStorageService objectStorageService;
    private final FfmpegProperties ffmpegProperties;

    @Override
    public AudioExtractionResult extract(VideoFile videoFile, TaskRecord taskRecord) {
        try {
            Path taskDir = Path.of(ffmpegProperties.getWorkDir(), "task-" + taskRecord.getId()).toAbsolutePath();
            Files.createDirectories(taskDir);

            Path inputVideo = taskDir.resolve("input" + resolveExtension(videoFile.getOriginalFilename()));
            Path outputAudio = taskDir.resolve("audio.wav");
            Path logFile = taskDir.resolve("ffmpeg.log");

            downloadVideo(videoFile, inputVideo);
            runFfmpeg(inputVideo, outputAudio, logFile);

            return AudioExtractionResult.builder()
                    .audioPath(outputAudio.toString())
                    .durationSeconds(null)
                    .build();
        } catch (BizException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BizException(500, "FFmpeg 提取音频失败：" + ex.getMessage());
        }
    }

    private void downloadVideo(VideoFile videoFile, Path inputVideo) throws Exception {
        if (!StringUtils.hasText(videoFile.getMinioBucket()) || !StringUtils.hasText(videoFile.getMinioObjectKey())) {
            throw new BizException(400, "视频缺少 MinIO 存储信息，无法提取音频");
        }
        try (InputStream inputStream = objectStorageService.getObject(videoFile.getMinioBucket(), videoFile.getMinioObjectKey())) {
            Files.copy(inputStream, inputVideo, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void runFfmpeg(Path inputVideo, Path outputAudio, Path logFile) throws Exception {
        List<String> command = List.of(
                ffmpegProperties.getBinaryPath(),
                "-y",
                "-i", inputVideo.toString(),
                "-vn",
                "-acodec", "pcm_s16le",
                "-ar", "16000",
                "-ac", "1",
                outputAudio.toString()
        );

        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(logFile.toFile())
                .start();

        boolean finished = process.waitFor(FFMPEG_TIMEOUT.toSeconds(), java.util.concurrent.TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new BizException(500, "FFmpeg 执行超时，请检查视频文件或调大超时时间");
        }
        if (process.exitValue() != 0) {
            throw new BizException(500, "FFmpeg 执行失败，日志文件：" + logFile);
        }
        if (!Files.exists(outputAudio) || Files.size(outputAudio) == 0) {
            throw new BizException(500, "FFmpeg 未生成有效音频文件，日志文件：" + logFile);
        }
    }

    private String resolveExtension(String filename) {
        String cleanName = StringUtils.hasText(filename) ? filename : "video.mp4";
        int index = cleanName.lastIndexOf('.');
        if (index < 0 || index == cleanName.length() - 1) {
            return ".mp4";
        }
        return cleanName.substring(index);
    }
}

