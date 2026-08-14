package com.videomind.module.task.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.videomind.common.enums.TaskStatus;
import com.videomind.common.exception.BizException;
import com.videomind.module.task.analysis.AudioExtractorClient;
import com.videomind.module.task.analysis.SpeechToTextClient;
import com.videomind.module.task.analysis.VideoSummaryClient;
import com.videomind.module.task.analysis.dto.AsrResult;
import com.videomind.module.task.analysis.dto.AudioExtractionResult;
import com.videomind.module.task.analysis.dto.SummaryResult;
import com.videomind.module.task.entity.AiSummaryResult;
import com.videomind.module.task.entity.TaskRecord;
import com.videomind.module.task.entity.VideoTranscription;
import com.videomind.module.task.mapper.AiSummaryResultMapper;
import com.videomind.module.task.mapper.VideoTranscriptionMapper;
import com.videomind.module.task.mq.VideoAnalyzeMessage;
import com.videomind.module.knowledge.service.KnowledgeService;
import com.videomind.module.knowledge.timeline.VideoTimelinePipeline;
import com.videomind.module.agent.service.MindAgentVideoSyncService;
import com.videomind.module.task.service.TaskRecordService;
import com.videomind.module.task.service.VideoAnalyzeProcessorService;
import com.videomind.module.video.entity.VideoFile;
import com.videomind.module.video.service.VideoFileService;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.time.LocalDateTime;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

@Slf4j
@Service
@RequiredArgsConstructor
public class VideoAnalyzeProcessorServiceImpl implements VideoAnalyzeProcessorService {

    private final TaskRecordService taskRecordService;
    private final VideoFileService videoFileService;
    private final AudioExtractorClient audioExtractorClient;
    private final SpeechToTextClient speechToTextClient;
    private final VideoSummaryClient videoSummaryClient;
    private final VideoTranscriptionMapper videoTranscriptionMapper;
    private final AiSummaryResultMapper aiSummaryResultMapper;
    private final KnowledgeService knowledgeService;
    private final VideoTimelinePipeline videoTimelinePipeline;
    private final MindAgentVideoSyncService mindAgentVideoSyncService;
    private final RedissonClient redissonClient;

    @Override
    public void process(VideoAnalyzeMessage message) {
        TaskRecord currentTask = taskRecordService.getTask(message.getTaskId(), message.getUserId());
        if (currentTask.getTaskStatus() == TaskStatus.SUCCESS || currentTask.getTaskStatus() == TaskStatus.FAILED) {
            log.info("Skip finished video analyze task, taskId={}, status={}",
                    currentTask.getId(), currentTask.getTaskStatus());
            return;
        }
        String mode = normalizeMode(message.getAnalysisMode());
        RLock lock = redissonClient.getLock("lock:analyze:md5:" + message.getVideoMd5());
        boolean locked = false;
        try {
            locked = lock.tryLock(5, TimeUnit.SECONDS);
            if (!locked) {
                log.info("Retry duplicate video analyze message because lock is busy, taskId={}, md5={}",
                        message.getTaskId(), message.getVideoMd5());
                throw new LockBusyException("同一视频正在解析中，等待 RocketMQ 重试");
            }
            TaskRecord taskRecord = taskRecordService.markProcessing(message.getTaskId(), message.getUserId());
            if (taskRecord.getTaskStatus() == TaskStatus.SUCCESS) {
                log.info("Video analyze task already completed, taskId={}", message.getTaskId());
                return;
            }

            VideoFile videoFile = videoFileService.getVideoDetail(message.getVideoId(), message.getUserId());
            VideoTranscription reusableTranscript = latestTranscription(videoFile.getId(), message.getUserId());
            AsrResult asrResult;
            SavedTranscription savedTranscription;
            if (reusableTranscript != null && videoFile.getTranscriptVersion() != null
                    && videoFile.getTranscriptVersion() > 0) {
                asrResult = AsrResult.builder()
                        .text(reusableTranscript.getTranscriptionText())
                        .language(reusableTranscript.getLanguage())
                        .build();
                savedTranscription = new SavedTranscription(reusableTranscript, false);
                log.info("Reuse existing transcription, taskId={}, videoId={}, mode={}",
                        taskRecord.getId(), videoFile.getId(), mode);
            } else {
                AudioExtractionResult audio = audioExtractorClient.extract(videoFile, taskRecord);
                if (audio.getDurationSeconds() != null && audio.getDurationSeconds() > 0) {
                    videoFile.setDurationSeconds(audio.getDurationSeconds());
                    videoFileService.updateById(videoFile);
                }
                asrResult = speechToTextClient.transcribe(audio, videoFile, taskRecord);
                savedTranscription = saveTranscription(taskRecord, asrResult);
            }
            int transcriptVersion = updateTranscriptVersion(videoFile, savedTranscription.created());
            videoTimelinePipeline.build(taskRecord, videoFile, transcriptVersion, asrResult);
            if ("ADVANCED".equals(mode)) {
                mindAgentVideoSyncService.sync(videoFile.getId(), message.getUserId(), taskRecord.getId());
                log.info("Advanced analysis dispatched to Agent Platform, taskId={}, transcriptVersion={}",
                        taskRecord.getId(), transcriptVersion);
                return;
            }
            runLegacyPostAsr(videoFile, taskRecord, asrResult);
            taskRecordService.markSuccess(message.getTaskId(), message.getUserId());
            runAutoVectorization(taskRecord);
            log.info("Video analyze task completed, taskId={}", message.getTaskId());
        } catch (Exception ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            if (isRetryable(ex)) {
                taskRecordService.markRetrying(message.getTaskId(), message.getUserId(), ex.getMessage());
                log.warn("Video analyze task will be retried by RocketMQ, taskId={}, reason={}",
                        message.getTaskId(), ex.getMessage(), ex);
                throw toRuntimeException(ex);
            }
            taskRecordService.markFailed(message.getTaskId(), message.getUserId(), ex.getMessage());
            log.error("Video analyze task failed permanently, taskId={}", message.getTaskId(), ex);
        } finally {
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private static class LockBusyException extends RuntimeException {

        private LockBusyException(String message) {
            super(message);
        }
    }

    private boolean isRetryable(Exception ex) {
        if (ex instanceof LockBusyException) {
            return true;
        }
        if (ex instanceof InterruptedException) {
            return true;
        }
        if (ex instanceof BizException bizException) {
            Integer code = bizException.getCode();
            return code != null && (code == 429 || code >= 500);
        }
        if (ex instanceof RestClientResponseException responseException) {
            int statusCode = responseException.getRawStatusCode();
            return statusCode == 429 || statusCode >= 500;
        }
        if (ex instanceof ResourceAccessException) {
            return true;
        }
        return hasRetryableCause(ex);
    }

    private boolean hasRetryableCause(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof SocketTimeoutException
                    || current instanceof ConnectException
                    || current instanceof TimeoutException
                    || current instanceof IOException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private RuntimeException toRuntimeException(Exception ex) {
        if (ex instanceof RuntimeException runtimeException) {
            return runtimeException;
        }
        return new BizException(500, ex.getMessage());
    }

    private SavedTranscription saveTranscription(TaskRecord taskRecord, AsrResult asrResult) {
        VideoTranscription existing = videoTranscriptionMapper.selectOne(new LambdaQueryWrapper<VideoTranscription>()
                .eq(VideoTranscription::getTaskId, taskRecord.getId()));
        if (existing != null) {
            existing.setLanguage(asrResult.getLanguage());
            existing.setTranscriptionText(asrResult.getText());
            existing.setUpdatedTime(LocalDateTime.now());
            videoTranscriptionMapper.updateById(existing);
            return new SavedTranscription(existing, false);
        }

        LocalDateTime now = LocalDateTime.now();
        VideoTranscription transcription = new VideoTranscription();
        transcription.setTaskId(taskRecord.getId());
        transcription.setVideoId(taskRecord.getVideoId());
        transcription.setUserId(taskRecord.getUserId());
        transcription.setLanguage(asrResult.getLanguage());
        transcription.setTranscriptionText(asrResult.getText());
        transcription.setCreatedTime(now);
        transcription.setUpdatedTime(now);
        videoTranscriptionMapper.insert(transcription);
        return new SavedTranscription(transcription, true);
    }

    private VideoTranscription latestTranscription(Long videoId, Long userId) {
        return videoTranscriptionMapper.selectOne(new LambdaQueryWrapper<VideoTranscription>()
                .eq(VideoTranscription::getVideoId, videoId)
                .eq(VideoTranscription::getUserId, userId)
                .orderByDesc(VideoTranscription::getUpdatedTime)
                .last("LIMIT 1"));
    }

    private int updateTranscriptVersion(VideoFile videoFile, boolean isNewTranscription) {
        int current = videoFile.getTranscriptVersion() == null ? 0 : videoFile.getTranscriptVersion();
        int version = isNewTranscription ? current + 1 : Math.max(1, current);
        videoFile.setTranscriptVersion(version);
        if (isNewTranscription) {
            videoFile.setSummaryStatus("UNSYNCED");
            videoFile.setSummaryVersion(0);
            videoFile.setLatestSummaryId(null);
            videoFile.setAgentIngestVersion(0);
            videoFile.setAgentIngestStatus("UNSYNCED");
            videoFile.setAgentSourceKnowledgeBaseId(null);
            videoFile.setAgentReportKnowledgeBaseId(null);
            videoFile.setAgentReportStatus("UNSYNCED");
            videoFile.setAgentReportVersion(0);
            videoFile.setAgentReportProfile(null);
            videoFile.setAgentLastError(null);
        }
        videoFile.setAgentUpdatedAt(LocalDateTime.now());
        videoFileService.updateById(videoFile);
        return version;
    }

    private String normalizeMode(String mode) {
        return "ADVANCED".equalsIgnoreCase(mode) ? "ADVANCED" : "NORMAL";
    }

    private void runLegacyPostAsr(VideoFile videoFile, TaskRecord taskRecord, AsrResult asrResult) {
        SummaryResult summaryResult = videoSummaryClient.summarize(asrResult, videoFile, taskRecord);
        saveSummary(taskRecord, summaryResult);
        videoFile.setSummaryStatus("SUCCESS");
        videoFile.setSummaryVersion(videoFile.getTranscriptVersion());
        videoFile.setAgentUpdatedAt(LocalDateTime.now());
        videoFileService.updateById(videoFile);
    }

    private void runAutoVectorization(TaskRecord taskRecord) {
        if (Boolean.TRUE.equals(taskRecord.getAutoVectorize())) {
            try {
                knowledgeService.vectorizeTask(taskRecord.getId(), taskRecord.getUserId());
            } catch (Exception ex) {
                log.error("Auto vectorize failed, taskId={}", taskRecord.getId(), ex);
            }
        }
    }

    private record SavedTranscription(VideoTranscription transcription, boolean created) {
    }

    private void saveSummary(TaskRecord taskRecord, SummaryResult summaryResult) {
        AiSummaryResult existing = aiSummaryResultMapper.selectOne(new LambdaQueryWrapper<AiSummaryResult>()
                .eq(AiSummaryResult::getTaskId, taskRecord.getId()));
        if (existing != null) {
            existing.setSummaryText(summaryResult.getSummaryText());
            existing.setSummaryJson(summaryResult.getSummaryJson());
            existing.setModelName(summaryResult.getModelName());
            existing.setUpdatedTime(LocalDateTime.now());
            aiSummaryResultMapper.updateById(existing);
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        AiSummaryResult summary = new AiSummaryResult();
        summary.setTaskId(taskRecord.getId());
        summary.setVideoId(taskRecord.getVideoId());
        summary.setUserId(taskRecord.getUserId());
        summary.setSummaryText(summaryResult.getSummaryText());
        summary.setSummaryJson(summaryResult.getSummaryJson());
        summary.setModelName(summaryResult.getModelName());
        summary.setCreatedTime(now);
        summary.setUpdatedTime(now);
        aiSummaryResultMapper.insert(summary);
    }
}
