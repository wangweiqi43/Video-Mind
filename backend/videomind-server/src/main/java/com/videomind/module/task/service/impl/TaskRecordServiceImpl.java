package com.videomind.module.task.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.videomind.common.enums.TaskStatus;
import com.videomind.common.exception.BizException;
import com.videomind.config.AiProperties;
import com.videomind.config.RateLimitProperties;
import com.videomind.infrastructure.ratelimit.RateLimitService;
import com.videomind.module.task.dto.AnalyzeTaskCreateRequest;
import com.videomind.module.task.dto.AnalyzeTaskCreateResponse;
import com.videomind.module.task.entity.AiSummaryResult;
import com.videomind.module.task.entity.TaskRecord;
import com.videomind.module.task.mapper.AiSummaryResultMapper;
import com.videomind.module.task.mapper.TaskRecordMapper;
import com.videomind.module.task.mq.AnalyzeTaskMessageProducer;
import com.videomind.module.task.mq.VideoAnalyzeMessage;
import com.videomind.module.task.service.TaskRecordService;
import com.videomind.module.video.entity.VideoFile;
import com.videomind.module.video.service.VideoFileService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class TaskRecordServiceImpl extends ServiceImpl<TaskRecordMapper, TaskRecord> implements TaskRecordService {

    private static final String LOCAL_MODE = "LOCAL";

    private final VideoFileService videoFileService;
    private final AnalyzeTaskMessageProducer analyzeTaskMessageProducer;
    private final RateLimitService rateLimitService;
    private final RateLimitProperties rateLimitProperties;
    private final AiSummaryResultMapper aiSummaryResultMapper;
    private final AiProperties aiProperties;
    private final RedissonClient redissonClient;

    @Override
    public AnalyzeTaskCreateResponse createAnalyzeTask(AnalyzeTaskCreateRequest request, Long userId) {
        rateLimitService.acquire("analyze:user:" + userId, rateLimitProperties.getAnalyzePermitsPerMinute());
        VideoFile videoFile = videoFileService.getVideoDetail(request.getVideoId(), userId);
        RLock lock = redissonClient.getLock("lock:analyze:create:md5:" + videoFile.getFileMd5());
        boolean locked = false;
        try {
            locked = lock.tryLock(3, 10, TimeUnit.SECONDS);
            if (!locked) {
                throw new BizException(429, "同一视频解析任务正在创建中，请稍后再试");
            }
            return createAnalyzeTaskInLock(request, userId, videoFile);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new BizException(500, "创建解析任务被中断");
        } finally {
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private AnalyzeTaskCreateResponse createAnalyzeTaskInLock(
            AnalyzeTaskCreateRequest request,
            Long userId,
            VideoFile videoFile) {
        TaskRecord reusedTask = getOne(new LambdaQueryWrapper<TaskRecord>()
                .eq(TaskRecord::getUserId, userId)
                .eq(TaskRecord::getVideoMd5, videoFile.getFileMd5())
                .eq(TaskRecord::getAnalysisMode, LOCAL_MODE)
                .eq(TaskRecord::getTaskStatus, TaskStatus.SUCCESS)
                .orderByDesc(TaskRecord::getCreatedTime)
                .last("LIMIT 1"));
        if (reusedTask != null && isReusableResult(reusedTask, videoFile)) {
            return buildReusedResponse(reusedTask);
        }

        TaskRecord runningTask = getOne(new LambdaQueryWrapper<TaskRecord>()
                .eq(TaskRecord::getUserId, userId)
                .eq(TaskRecord::getVideoMd5, videoFile.getFileMd5())
                .eq(TaskRecord::getAnalysisMode, LOCAL_MODE)
                .in(TaskRecord::getTaskStatus, List.of(TaskStatus.PENDING, TaskStatus.PROCESSING, TaskStatus.RETRYING))
                .orderByDesc(TaskRecord::getCreatedTime)
                .last("LIMIT 1"));
        if (runningTask != null) {
            return buildReusedResponse(runningTask);
        }

        TaskRecord taskRecord = new TaskRecord();
        taskRecord.setUserId(userId);
        taskRecord.setVideoId(videoFile.getId());
        taskRecord.setVideoMd5(videoFile.getFileMd5());
        taskRecord.setTaskStatus(TaskStatus.PENDING);
        taskRecord.setAutoVectorize(false);
        taskRecord.setAnalysisMode(LOCAL_MODE);
        taskRecord.setRetryCount(0);
        LocalDateTime now = LocalDateTime.now();
        taskRecord.setCreatedTime(now);
        taskRecord.setUpdatedTime(now);
        save(taskRecord);

        try {
            analyzeTaskMessageProducer.send(VideoAnalyzeMessage.builder()
                    .taskId(taskRecord.getId())
                    .videoId(videoFile.getId())
                    .userId(userId)
                    .videoMd5(videoFile.getFileMd5())
                    .build());
        } catch (Exception ex) {
            markFailed(taskRecord.getId(), userId, ex.getMessage());
            throw ex;
        }

        return AnalyzeTaskCreateResponse.builder()
                .taskId(taskRecord.getId())
                .status(taskRecord.getTaskStatus())
                .reused(false)
                .build();
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
