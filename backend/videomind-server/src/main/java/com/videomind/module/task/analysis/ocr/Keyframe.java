package com.videomind.module.task.analysis.ocr;

import java.nio.file.Path;

public record Keyframe(long timestampMs, Path imagePath) {
}
