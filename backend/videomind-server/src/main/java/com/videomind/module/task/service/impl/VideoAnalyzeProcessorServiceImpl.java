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
import com.videomind.module.task.service.TaskRecordService;
import com.videomind.module.task.service.VideoAnalyzeProcessorService;
import com.videomind.module.video.entity.VideoFile;
import com.videomind.module.video.service.VideoFileService;
import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

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
    private final RedissonClient redissonClient;

    @Override
    public void process(VideoAnalyzeMessage message) {
        TaskRecord currentTask = taskRecordService.getTask(message.getTaskId(), message.getUserId());
        if (currentTask.getTaskStatus() == TaskStatus.SUCCESS || currentTask.getTaskStatus() == TaskStatus.FAILED) {
            log.info("Skip finished video analyze task, taskId={}, status={}",
                    currentTask.getId(), currentTask.getTaskStatus());
            return;
        }
        TaskRecord reusableTask = taskRecordService.getLatestSuccessfulTaskByVideo(message.getVideoId(), message.getUserId());
        if (reusableTask != null && !reusableTask.getId().equals(message.getTaskId())) {
            taskRecordService.markFailed(message.getTaskId(), message.getUserId(), "已有成功解析结果，跳过重复任务");
            log.info("Skip duplicate video analyze task because reusable result exists, taskId={}, reusableTaskId={}",
                    message.getTaskId(), reusableTask.getId());
            return;
        }

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
            AudioExtractionResult audio = audioExtractorClient.extract(videoFile, taskRecord);
            AsrResult asrResult = speechToTextClient.transcribe(audio, videoFile, taskRecord);
            SummaryResult summaryResult = videoSummaryClient.summarize(asrResult, videoFile, taskRecord);

            saveTranscription(taskRecord, asrResult);
            saveSummary(taskRecord, summaryResult);
            taskRecordService.markSuccess(message.getTaskId(), message.getUserId());
            if (Boolean.TRUE.equals(taskRecord.getAutoVectorize())) {
                try {
                    knowledgeService.vectorizeTask(taskRecord.getId(), taskRecord.getUserId());
                } catch (Exception ex) {
                    log.error("Auto vectorize failed, taskId={}", taskRecord.getId(), ex);
                }
            }
            log.info("Video analyze task completed, taskId={}", message.getTaskId());
        } catch (LockBusyException ex) {
            throw ex;
        } catch (Exception ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            taskRecordService.markFailed(message.getTaskId(), message.getUserId(), ex.getMessage());
            log.error("Video analyze task failed, taskId={}", message.getTaskId(), ex);
            if (ex instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new BizException(500, ex.getMessage());
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

    private void saveTranscription(TaskRecord taskRecord, AsrResult asrResult) {
        VideoTranscription existing = videoTranscriptionMapper.selectOne(new LambdaQueryWrapper<VideoTranscription>()
                .eq(VideoTranscription::getTaskId, taskRecord.getId()));
        if (existing != null) {
            existing.setLanguage(asrResult.getLanguage());
            existing.setTranscriptionText(asrResult.getText());
            existing.setUpdatedTime(LocalDateTime.now());
            videoTranscriptionMapper.updateById(existing);
            return;
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
