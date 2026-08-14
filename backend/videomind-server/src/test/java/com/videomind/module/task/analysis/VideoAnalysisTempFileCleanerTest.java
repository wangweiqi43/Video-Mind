package com.videomind.module.task.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import com.videomind.config.FfmpegProperties;
import com.videomind.module.task.entity.TaskRecord;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VideoAnalysisTempFileCleanerTest {
    @TempDir
    Path tempDir;

    @Test
    void removesOnlyTheExactCancelledTaskWorkspace() throws Exception {
        Path target = Files.createDirectories(tempDir.resolve("task-9").resolve("frames"));
        Files.writeString(target.resolve("frame.jpg"), "frame");
        Path unrelated = Files.createDirectories(tempDir.resolve("task-90"));
        Files.writeString(unrelated.resolve("keep.txt"), "keep");
        FfmpegProperties properties = new FfmpegProperties();
        properties.setWorkDir(tempDir.toString());
        TaskRecord task = new TaskRecord();
        task.setId(9L);

        new VideoAnalysisTempFileCleaner(properties).cleanup(task);

        assertThat(tempDir.resolve("task-9")).doesNotExist();
        assertThat(unrelated.resolve("keep.txt")).exists();
    }
}
