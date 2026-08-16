package com.videomind.module.task.analysis.ocr;

import com.videomind.common.exception.BizException;
import com.videomind.config.FfmpegProperties;
import com.videomind.config.OcrProperties;
import com.videomind.infrastructure.storage.ObjectStorageService;
import com.videomind.module.task.entity.TaskRecord;
import com.videomind.module.video.entity.VideoFile;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@ConditionalOnProperty(prefix = "videomind.ffmpeg", name = "mode", havingValue = "ffmpeg", matchIfMissing = true)
public class FfmpegKeyframeExtractor implements KeyframeExtractor {
    private static final Duration TIMEOUT = Duration.ofMinutes(15);
    private static final Pattern PTS_TIME = Pattern.compile("pts_time:([0-9]+(?:\\.[0-9]+)?)");
    private final ObjectStorageService storage;
    private final FfmpegProperties ffmpeg;
    private final OcrProperties ocr;

    public FfmpegKeyframeExtractor(ObjectStorageService storage, FfmpegProperties ffmpeg, OcrProperties ocr) {
        this.storage = storage;
        this.ffmpeg = ffmpeg;
        this.ocr = ocr;
    }

    @Override
    public List<Keyframe> extract(VideoFile videoFile, TaskRecord taskRecord) {
        try {
            Path taskDir = Path.of(ffmpeg.getWorkDir(), "task-" + taskRecord.getId()).toAbsolutePath();
            Path frameDir = taskDir.resolve("keyframes");
            Files.createDirectories(frameDir);
            Path input = locateOrDownloadVideo(taskDir, videoFile);
            Path logFile = frameDir.resolve("ffmpeg-keyframes.log");
            List<String> command = command(input, frameDir.resolve("frame-%06d.jpg"));
            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .redirectOutput(logFile.toFile())
                    .start();
            if (!process.waitFor(TIMEOUT.toSeconds(), java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new BizException(500, "FFmpeg 关键帧提取超时");
            }
            if (process.exitValue() != 0) {
                throw new BizException(500, "FFmpeg 关键帧提取失败，日志：" + logFile);
            }
            List<Long> timestamps = parseTimestamps(Files.readString(logFile, StandardCharsets.UTF_8));
            List<Path> images;
            try (var stream = Files.list(frameDir)) {
                images = stream.filter(path -> path.getFileName().toString().matches("frame-\\d{6}\\.jpg"))
                        .sorted(Comparator.comparing(Path::toString))
                        .toList();
            }
            List<Keyframe> frames = new ArrayList<>();
            for (int index = 0; index < images.size(); index++) {
                long timestamp = index < timestamps.size()
                        ? timestamps.get(index)
                        : (long) index * ocr.getMaxIntervalSeconds() * 1_000;
                frames.add(new Keyframe(timestamp, images.get(index)));
            }
            List<Keyframe> retained = retainCoverage(frames,
                    Math.multiplyExact(ocr.getMaxIntervalSeconds(), 1_000L), ocr.getMaxFrames());
            Set<Path> retainedPaths = new HashSet<>(retained.stream().map(Keyframe::imagePath).toList());
            for (Keyframe frame : frames) {
                if (!retainedPaths.contains(frame.imagePath())) {
                    Files.deleteIfExists(frame.imagePath());
                }
            }
            return retained;
        } catch (BizException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BizException(500, "关键帧提取异常：" + exception.getMessage());
        }
    }

    List<String> command(Path input, Path outputPattern) {
        String filter = String.format(Locale.ROOT,
                "select='isnan(prev_selected_t)+gt(scene,%.3f)+gte(t-prev_selected_t,%d)',showinfo",
                ocr.getSceneThreshold(), ocr.getMaxIntervalSeconds());
        return List.of(ffmpeg.getBinaryPath(), "-y", "-i", input.toString(), "-vf", filter,
                "-fps_mode", "vfr", "-q:v", "2", outputPattern.toString());
    }

    /**
     * Keeps bounded scene-change extras without ever dropping the periodic coverage guarantee.
     * Once the scene budget is exhausted, the next candidate at least one max interval away is retained.
     */
    static List<Keyframe> retainCoverage(List<Keyframe> candidates, long maxIntervalMs, int maxSceneFrames) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        List<Keyframe> ordered = candidates.stream().filter(java.util.Objects::nonNull)
                .sorted(Comparator.comparingLong(Keyframe::timestampMs))
                .toList();
        List<Keyframe> retained = new ArrayList<>();
        long lastRetainedMs = Long.MIN_VALUE;
        int sceneFrames = 0;
        for (Keyframe candidate : ordered) {
            boolean coverageRequired = retained.isEmpty()
                    || candidate.timestampMs() - lastRetainedMs >= maxIntervalMs;
            if (coverageRequired || sceneFrames < Math.max(0, maxSceneFrames)) {
                retained.add(candidate);
                lastRetainedMs = candidate.timestampMs();
                if (!coverageRequired) {
                    sceneFrames++;
                }
            }
        }
        return List.copyOf(retained);
    }

    static List<Long> parseTimestamps(String log) {
        List<Long> timestamps = new ArrayList<>();
        Matcher matcher = PTS_TIME.matcher(log == null ? "" : log);
        while (matcher.find()) {
            timestamps.add(Math.round(Double.parseDouble(matcher.group(1)) * 1_000));
        }
        return List.copyOf(timestamps);
    }

    private Path locateOrDownloadVideo(Path taskDir, VideoFile videoFile) throws Exception {
        try (var stream = Files.list(taskDir)) {
            Path existing = stream.filter(path -> path.getFileName().toString().startsWith("input."))
                    .filter(Files::isRegularFile).findFirst().orElse(null);
            if (existing != null && Files.size(existing) > 0) {
                return existing;
            }
        }
        if (!StringUtils.hasText(videoFile.getMinioBucket()) || !StringUtils.hasText(videoFile.getMinioObjectKey())) {
            throw new BizException(400, "视频缺少 MinIO 存储信息，无法提取关键帧");
        }
        Path input = taskDir.resolve("input" + extension(videoFile.getOriginalFilename()));
        try (InputStream stream = storage.getObject(videoFile.getMinioBucket(), videoFile.getMinioObjectKey())) {
            Files.copy(stream, input, StandardCopyOption.REPLACE_EXISTING);
        }
        return input;
    }

    private String extension(String filename) {
        int index = StringUtils.hasText(filename) ? filename.lastIndexOf('.') : -1;
        return index >= 0 ? filename.substring(index) : ".mp4";
    }
}
