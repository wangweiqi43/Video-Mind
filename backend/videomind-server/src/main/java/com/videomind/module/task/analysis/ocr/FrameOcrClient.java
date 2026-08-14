package com.videomind.module.task.analysis.ocr;

import java.nio.file.Path;

public interface FrameOcrClient {
    OcrText recognize(Path imagePath);

    record OcrText(String text, double confidence) {
    }
}
