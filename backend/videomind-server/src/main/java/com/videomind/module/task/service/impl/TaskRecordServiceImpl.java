package com.videomind.module.task.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.videomind.common.enums.TaskStatus;
import com.videomind.common.exception.BizException;
import com.videomind.config.AiProperties;
import com.videomind.config.RateLimitProperties;
import com.videomind.config.TencentAsrProperties;
import com.videomind.infrastructure.ratelimit.RateLimitService;
import com.videomind.module.task.dto.AnalyzeTaskCreateRequest;
import com.videomind.module.task.dto.AnalyzeTaskCreateResponse;
import com.videomind.module.task.entity.AiSummaryResult;
import com.videomind.module.task.entity.TaskRecord;
import com.videomind.module.task.mapper.AiSummaryResultMapper;
import com.videomind.module.task.mapper.TaskRecordMapper;
import com.videomind.module.task.mapper.ProcessingTaskMapper;
import com.videomind.module.task.entity.ProcessingTask;
import com.videomind.common.enums.ProcessingTaskType;
import com.videomind.module.task.mq.TaskCreateCommand;
import com.videomind.module.task.mq.TaskDispatchResult;
import com.videomind.module.task.mq.TransactionalTaskMessageProducer;
import com.videomind.module.task.service.TaskRecordService;
import com.videomind.module.task.service.ProcessingTaskStateMachine;
import com.videomind.module.task.service.TaskRecordProjectionService;
import com.videomind.module.video.entity.VideoFile;
import com.videomind.module.video.service.VideoFileService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class TaskRecordServiceImpl extends ServiceImpl<TaskRecordMapper, TaskRecord> implements TaskRecordService {

    private final VideoFileService videoFileService;
    private final TransactionalTaskMessageProducer taskMessages;
    private final RateLimitService rateLimitService;
    private final RateLimitProperties rateLimitProperties;
    private final AiSummaryResultMapper aiSummaryResultMapper;
    private final AiProperties aiProperties;
    private final TencentAsrProperties tencentAsrProperties;
    private final ProcessingTaskMapper processingTasks;
    private final ProcessingTaskStateMachine processingState;
    private final TaskRecordProjectionService taskProjection;

    @Override
    public AnalyzeTaskCreateResponse createAnalyzeTask(AnalyzeTaskCreateRequest request, Long userId) {
        rateLimitService.acquire("analyze:user:" + userId, rateLimitProperties.getAnalyzePermitsPerMinute());
        VideoFile videoFile = videoFileService.getVideoDetail(request.getVideoId(), userId);
        TaskRecord reusedTask = getOne(new LambdaQueryWrapper<TaskRecord>()
                .eq(TaskRecord::getUserId, userId)
                .eq(TaskRecord::getVideoId, videoFile.getId())
                .eq(TaskRecord::getVideoMd5, videoFile.getFileMd5())
                .eq(TaskRecord::getTaskStatus, TaskStatus.SUCCESS)
                .orderByDesc(TaskRecord::getCreatedTime)
                .last("LIMIT 1"));
        if (reusedTask != null && isReusableResult(reusedTask, videoFile)) {
            return buildReusedResponse(reusedTask);
        }
        TaskCreateCommand command = new TaskCreateCommand(userId, ProcessingTaskType.VIDEO_ANALYSIS,
                videoFile.getId(), videoFingerprint(userId, videoFile), "START", 5,
                Map.of("videoMd5", videoFile.getFileMd5(), "timelineVersion", "timeline-fusion-v1"));
        TaskDispatchResult dispatched = taskMessages.dispatch(command);
        TaskRecord taskRecord = getTask(dispatched.businessId(), userId);
        return AnalyzeTaskCreateResponse.builder()
                .taskId(taskRecord.getId())
                .status(taskRecord.getTaskStatus())
                .reused(dispatched.reused())
                .build();
    }

    private String videoFingerprint(Long userId, VideoFile videoFile) {
        AiProperties.ApiProvider asr = aiProperties.getAsr();
        AiProperties.ApiProvider summary = aiProperties.getSummary();
        String signature = String.join("|", String.valueOf(userId), String.valueOf(videoFile.getId()),
                safe(videoFile.getFileMd5()), safe(asr.getMode()), safe(asr.getProvider()), safe(asr.getEndpoint()),
                safe(asr.getModel()), safe(tencentAsrProperties.getRegion()),
                safe(tencentAsrProperties.getEngineModelType()), safe(summary.getMode()), safe(summary.getModel()),
                safe(summary.getPromptVersion()), "timeline-fusion-v1");
        return "VIDEO_ANALYSIS:" + userId + ":" + videoFile.getId() + ":" + sha256(signature);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private AnalyzeTaskCreateResponse buildReusedResponse(TaskRecord taskRecord) {
        return AnalyzeTaskCreateResponse.builder()
                .taskId(taskRecord.getId())
                .status(taskRecord.getTaskStatus())
                .reused(true)
                .build();
    }

    private boolean isReusableResult(TaskRecord taskRecord, VideoFile videoFile) {
        if (!"SUCCESS".equalsIgnoreCase(videoFile.getSummaryStatus())
                || videoFile.getTranscriptVersion() == null
                || !videoFile.getTranscriptVersion().equals(videoFile.getSummaryVersion())) {
            return false;
        }
        AiSummaryResult summary = aiSummaryResultMapper.selectOne(new LambdaQueryWrapper<AiSummaryResult>()
                .eq(AiSummaryResult::getTaskId, taskRecord.getId()));
        if (summary == null || !StringUtils.hasText(summary.getModelName())) {
            return false;
        }

        String summaryMode = aiProperties.getSummary().getMode();
        String modelName = summary.getModelName();
        if ("mock".equalsIgnoreCase(summaryMode)) {
            String promptVersion = aiProperties.getSummary().getPromptVersion();
            String expectedModelName = "mock-summary@" + (StringUtils.hasText(promptVersion) ? promptVersion : "v1");
            return expectedModelName.equals(modelName);
        }
        if ("real".equalsIgnoreCase(summaryMode)) {
            String expectedModelName = currentSummaryModelSignature();
            return expectedModelName.equals(modelName);
        }
        return false;
    }

    private String currentSummaryModelSignature() {
        String configuredModel = aiProperties.getSummary().getModel();
        String promptVersion = aiProperties.getSummary().getPromptVersion();
        String model = StringUtils.hasText(configuredModel) ? configuredModel : "real-summary";
        String version = StringUtils.hasText(promptVersion) ? promptVersion : "v1";
        return model + "@" + version;
    }

    @Override
    public TaskRecord getLatestSuccessfulTaskByVideo(Long videoId, Long userId) {
        VideoFile videoFile = videoFileService.getVideoDetail(videoId, userId);
        TaskRecord taskRecord = getOne(new LambdaQueryWrapper<TaskRecord>()
                .eq(TaskRecord::getUserId, userId)
                .eq(TaskRecord::getVideoId, videoFile.getId())
                .eq(TaskRecord::getVideoMd5, videoFile.getFileMd5())
                .eq(TaskRecord::getTaskStatus, TaskStatus.SUCCESS)
                .orderByDesc(TaskRecord::getCreatedTime)
                .last("LIMIT 1"));
        return taskRecord;
    }

    @Override
    public TaskRecord getTask(Long taskId, Long userId) {
        TaskRecord taskRecord = getOne(new LambdaQueryWrapper<TaskRecord>()
                .eq(TaskRecord::getId, taskId)
                .eq(TaskRecord::getUserId, userId));
        if (taskRecord == null) {
            throw new BizException(404, "任务不存在或无权访问");
        }
        return taskRecord;
    }

    @Override
    public TaskRecord cancelTask(Long taskId, Long userId) {
        TaskRecord task = getTask(taskId, userId);
        if (task.getTaskStatus() == TaskStatus.SUCCESS || task.getTaskStatus() == TaskStatus.FAILED
                || task.getTaskStatus() == TaskStatus.CANCELLED) {
            return task;
        }
        ProcessingTask processing = processingTasks.selectOne(new LambdaQueryWrapper<ProcessingTask>()
                .eq(ProcessingTask::getTaskType, ProcessingTaskType.VIDEO_ANALYSIS)
                .eq(ProcessingTask::getBusinessId, taskId)
                .orderByDesc(ProcessingTask::getCreatedTime)
                .last("LIMIT 1"));
        if (processing == null) {
            throw new BizException(409, "任务缺少可取消的本地处理状态");
        }
        var result = processingState.requestCancel(processing.getId(), userId);
        if (result.status() == ProcessingTaskStateMachine.CancelRequestStatus.NOT_FOUND
                || result.status() == ProcessingTaskStateMachine.CancelRequestStatus.FORBIDDEN) {
            throw new BizException(404, "任务不存在或无权访问");
        }
        if (result.status() == ProcessingTaskStateMachine.CancelRequestStatus.CONFLICT) {
            throw new BizException(409, "任务状态正在变化，请重试取消");
        }
        taskProjection.project(processing.getId());
        return getTask(taskId, userId);
    }

    @Override
    public TaskRecord markProcessing(Long taskId, Long userId) {
        TaskRecord taskRecord = getTask(taskId, userId);
        if (taskRecord.getTaskStatus() == TaskStatus.SUCCESS) {
            return taskRecord;
        }
        taskRecord.setTaskStatus(TaskStatus.PROCESSING);
        taskRecord.setStartedTime(LocalDateTime.now());
        taskRecord.setUpdatedTime(LocalDateTime.now());
        updateById(taskRecord);
        update(new LambdaUpdateWrapper<TaskRecord>()
                .eq(TaskRecord::getId, taskId)
                .set(TaskRecord::getErrorMessage, null)
                .set(TaskRecord::getFinishedTime, null));
        return taskRecord;
    }

    @Override
    public void markSuccess(Long taskId, Long userId) {
        TaskRecord taskRecord = getTask(taskId, userId);
        taskRecord.setTaskStatus(TaskStatus.SUCCESS);
        taskRecord.setFinishedTime(LocalDateTime.now());
        taskRecord.setUpdatedTime(LocalDateTime.now());
        taskRecord.setErrorMessage(null);
        updateById(taskRecord);
    }

    @Override
    public void markRetrying(Long taskId, Long userId, String errorMessage) {
        TaskRecord taskRecord = getTask(taskId, userId);
        if (taskRecord.getTaskStatus() == TaskStatus.SUCCESS || taskRecord.getTaskStatus() == TaskStatus.FAILED) {
            return;
        }
        taskRecord.setTaskStatus(TaskStatus.RETRYING);
        taskRecord.setRetryCount((taskRecord.getRetryCount() == null ? 0 : taskRecord.getRetryCount()) + 1);
        taskRecord.setUpdatedTime(LocalDateTime.now());
        taskRecord.setErrorMessage(errorMessage == null ? "等待 RocketMQ 重试" : errorMessage);
        updateById(taskRecord);
    }

    @Override
    public void markFailed(Long taskId, Long userId, String errorMessage) {
        TaskRecord taskRecord = getTask(taskId, userId);
        taskRecord.setTaskStatus(TaskStatus.FAILED);
        taskRecord.setFinishedTime(LocalDateTime.now());
        taskRecord.setUpdatedTime(LocalDateTime.now());
        taskRecord.setErrorMessage(errorMessage == null ? "未知错误" : errorMessage);
        updateById(taskRecord);
    }
}
