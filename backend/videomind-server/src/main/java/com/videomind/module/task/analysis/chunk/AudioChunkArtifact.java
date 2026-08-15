package com.videomind.module.task.analysis.chunk;

import java.nio.file.Path;

public record AudioChunkArtifact(
        AudioChunkPlan plan,
        Path path,
        String sha256,
        long sizeBytes
) {
}
