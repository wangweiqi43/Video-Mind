package com.videomind.module.task.analysis.ocr;

import com.videomind.module.task.entity.TaskRecord;
import com.videomind.module.video.entity.VideoFile;
import java.util.List;

public interface KeyframeExtractor {
    List<Keyframe> extract(VideoFile videoFile, TaskRecord taskRecord);
}
