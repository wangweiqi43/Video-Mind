package com.videomind.module.knowledge.deletion;

import com.videomind.common.enums.ProcessingTaskType;
import com.videomind.module.task.service.ProcessingTaskHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class VideoDeletionHandler implements ProcessingTaskHandler {
    private final PhysicalDeletionCoordinator coordinator;

    @Override
    public ProcessingTaskType type() {
        return ProcessingTaskType.VIDEO_DELETE;
    }

    @Override
    public String handle(TaskExecutionContext context) throws Exception {
        return coordinator.deleteVideo(context);
    }
}
