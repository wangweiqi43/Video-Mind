package com.videomind.module.knowledge.deletion;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.videomind.common.enums.KnowledgeBaseType;
import com.videomind.common.enums.ProcessingTaskType;
import com.videomind.common.exception.BizException;
import com.videomind.module.knowledge.entity.KnowledgeBase;
import com.videomind.module.knowledge.mapper.KnowledgeBaseMapper;
import com.videomind.module.task.dto.DeletionTaskResponse;
import com.videomind.module.task.entity.ProcessingTask;
import com.videomind.module.task.mapper.ProcessingTaskMapper;
import com.videomind.module.task.mq.TaskCreateCommand;
import com.videomind.module.task.mq.TaskDispatchResult;
import com.videomind.module.task.mq.TransactionalTaskMessageProducer;
import com.videomind.module.task.service.ProcessingTaskStateMachine;
import com.videomind.module.task.service.TaskCheckpointService;
import com.videomind.module.video.service.VideoFileService;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class DeletionTaskApplicationService {
    private final KnowledgeBaseMapper knowledgeBases;
    private final VideoFileService videos;
    private final TransactionalTaskMessageProducer messages;
    private final ProcessingTaskMapper processingTasks;
    private final ProcessingTaskStateMachine stateMachine;
    private final TaskCheckpointService checkpoints;

    public DeletionTaskResponse deleteKnowledgeBase(Long userId, Long knowledgeBaseId) {
        KnowledgeBase value = knowledgeBases.selectOne(Wrappers.<KnowledgeBase>lambdaQuery()
                .eq(KnowledgeBase::getId, knowledgeBaseId)
                .eq(KnowledgeBase::getUserId, userId)
                .last("LIMIT 1"));
        if (value == null) {
            throw new BizException(404, "知识库不存在或无权访问");
        }
        if (value.getType() != KnowledgeBaseType.USER) {
            throw new BizException(409, "视频知识库随视频管理，不能独立删除");
        }
        return dispatch(userId, ProcessingTaskType.KNOWLEDGE_DELETE, knowledgeBaseId);
    }

    public DeletionTaskResponse deleteVideo(Long userId, Long videoId) {
        videos.getVideoDetail(videoId, userId);
        return dispatch(userId, ProcessingTaskType.VIDEO_DELETE, videoId);
    }

    public Optional<DeletionTaskResponse> cancelIfDeletionTask(Long userId, Long taskId) {
        ProcessingTask task = deletionTask(taskId);
        if (task == null) {
            return Optional.empty();
        }
        if (!userId.equals(task.getUserId())) {
            throw new BizException(404, "任务不存在或无权访问");
        }
        if (checkpoints.isCompleted(taskId, PhysicalDeletionCoordinator.DELETION_STARTED)) {
            throw new BizException(409, "物理删除已经开始，不能取消");
        }
        ProcessingTaskStateMachine.CancelRequestResult result = stateMachine.requestCancel(taskId, userId);
        if (result.status() == ProcessingTaskStateMachine.CancelRequestStatus.NOT_FOUND
                || result.status() == ProcessingTaskStateMachine.CancelRequestStatus.FORBIDDEN) {
            throw new BizException(404, "任务不存在或无权访问");
        }
        if (result.status() == ProcessingTaskStateMachine.CancelRequestStatus.CONFLICT) {
            throw new BizException(409, "任务状态正在变化，请重试取消");
        }
        ProcessingTask refreshed = processingTasks.selectById(taskId);
        String status = refreshed == null || refreshed.getState() == null
                ? result.status().name() : refreshed.getState().name();
        return Optional.of(new DeletionTaskResponse(task.getEventId(), taskId, status));
    }

    public Optional<DeletionTaskResponse> findDeletionTask(Long userId, Long taskId) {
        ProcessingTask task = deletionTask(taskId);
        if (task == null) {
            return Optional.empty();
        }
        if (!userId.equals(task.getUserId())) {
            throw new BizException(404, "任务不存在或无权访问");
        }
        return Optional.of(new DeletionTaskResponse(task.getEventId(), task.getId(), task.getState().name()));
    }

    private DeletionTaskResponse dispatch(Long userId, ProcessingTaskType type, Long businessId) {
        String fingerprint = type.name() + ":" + userId + ":" + businessId;
        TaskCreateCommand command = new TaskCreateCommand(userId, type, businessId, fingerprint,
                "DELETE_QUEUED", 10, Map.of("targetId", businessId));
        TaskDispatchResult result = messages.dispatch(command);
        ProcessingTask task = processingTasks.selectById(result.processingTaskId());
        String status = task == null || task.getState() == null ? "PENDING" : task.getState().name();
        return new DeletionTaskResponse(result.eventId(), result.processingTaskId(), status);
    }

    private ProcessingTask deletionTask(Long taskId) {
        ProcessingTask task = processingTasks.selectById(taskId);
        return task != null && (task.getTaskType() == ProcessingTaskType.KNOWLEDGE_DELETE
                || task.getTaskType() == ProcessingTaskType.VIDEO_DELETE) ? task : null;
    }
}
