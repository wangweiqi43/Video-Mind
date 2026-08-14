package com.videomind.module.task.analysis;

import com.videomind.config.FfmpegProperties;
import com.videomind.module.task.entity.TaskRecord;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class VideoAnalysisTempFileCleaner {
    private final FfmpegProperties properties;

    public void cleanup(TaskRecord task) {
        Path root = Path.of(properties.getWorkDir()).toAbsolutePath().normalize();
        Path target = root.resolve("task-" + task.getId()).normalize();
        if (target.equals(root) || !target.startsWith(root) || !Files.exists(target)) {
            return;
        }
        try (var paths = Files.walk(target)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (Exception failure) {
            log.warn("Failed to clean cancelled video task workspace, taskId={}, path={}",
                    task.getId(), target, failure);
        }
    }
}
