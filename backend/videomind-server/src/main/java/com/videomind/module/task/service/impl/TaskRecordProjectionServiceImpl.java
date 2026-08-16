package com.videomind.module.task.service.impl;

import com.videomind.common.enums.ProcessingTaskState;
import com.videomind.common.enums.ProcessingTaskType;
import com.videomind.common.enums.TaskStatus;
import com.videomind.module.task.entity.ProcessingTask;
import com.videomind.module.task.entity.TaskRecord;
import com.videomind.module.task.mapper.ProcessingTaskMapper;
import com.videomind.module.task.mapper.TaskRecordMapper;
import com.videomind.module.task.service.TaskRecordProjectionService;
import com.videomind.common.enums.KnowledgeLifecycleStatus;
import com.videomind.module.knowledge.entity.KnowledgeDocument;
import com.videomind.module.knowledge.entity.KnowledgeBase;
import com.videomind.module.knowledge.entity.DocumentVersion;
import com.videomind.module.knowledge.mapper.KnowledgeDocumentMapper;
import com.videomind.module.knowledge.mapper.KnowledgeBaseMapper;
import com.videomind.module.knowledge.mapper.DocumentVersionMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class TaskRecordProjectionServiceImpl implements TaskRecordProjectionService {
    private final ProcessingTaskMapper processingTasks;
    private final TaskRecordMapper taskRecords;
    private final KnowledgeDocumentMapper documents;
    private final KnowledgeBaseMapper knowledgeBases;
    private final DocumentVersionMapper versions;

    @Override
    public void project(Long processingTaskId) {
        ProcessingTask source = processingTasks.selectById(processingTaskId);
        if (source == null) {
            return;
        }
        if (source.getTaskType() == ProcessingTaskType.DOCUMENT_INGEST) {
            projectDocument(source);
            return;
        }
        if (source.getTaskType() != ProcessingTaskType.VIDEO_ANALYSIS) return;
        TaskRecord target = taskRecords.selectById(source.getBusinessId());
        if (target == null || !source.getUserId().equals(target.getUserId())) {
            throw new IllegalStateException("VIDEO_TASK_PROJECTION_TARGET_MISSING");
        }
        TaskStatus status = status(source.getState());
        target.setTaskStatus(status);
        target.setUpdatedTime(LocalDateTime.now());
        if (status == TaskStatus.PROCESSING && target.getStartedTime() == null) {
            target.setStartedTime(source.getStartedTime() == null ? LocalDateTime.now() : source.getStartedTime());
        }
        if (status == TaskStatus.RETRYING) {
            target.setRetryCount(Math.max(value(target.getRetryCount()), value(source.getAttemptCount())));
            target.setErrorMessage(source.getErrorMessage());
        } else if (status == TaskStatus.FAILED || status == TaskStatus.CANCELLED) {
            target.setRetryCount(Math.max(value(target.getRetryCount()), Math.max(0, value(source.getAttemptCount()) - 1)));
            target.setErrorMessage(status == TaskStatus.CANCELLED ? null
                    : source.getErrorMessage() == null ? source.getErrorCode() : source.getErrorMessage());
            target.setFinishedTime(source.getFinishedTime() == null ? LocalDateTime.now() : source.getFinishedTime());
        } else if (status == TaskStatus.SUCCESS) {
            target.setErrorMessage(null);
            target.setFinishedTime(source.getFinishedTime() == null ? LocalDateTime.now() : source.getFinishedTime());
        } else {
            target.setErrorMessage(null);
            target.setFinishedTime(null);
        }
        taskRecords.updateById(target);
    }

    private void projectDocument(ProcessingTask source) {
        KnowledgeDocument document = documents.selectById(source.getBusinessId());
        if (document == null || !source.getUserId().equals(document.getUserId())) return;
        DocumentVersion version = versions.selectOne(Wrappers.<DocumentVersion>lambdaQuery()
                .eq(DocumentVersion::getDocumentId, document.getId())
                .orderByDesc(DocumentVersion::getVersionNumber).last("LIMIT 1"));
        LocalDateTime now = LocalDateTime.now();
        if (version != null) {
            version.setProcessingStage(source.getStage());
            version.setUpdatedTime(now);
            versions.updateById(version);
        }
        switch (source.getState()) {
            case PENDING, PROCESSING, RETRY_WAIT, CANCEL_REQUESTED -> {
                document.setStatus(KnowledgeLifecycleStatus.PROCESSING);
                document.setFailureCode(source.getState() == ProcessingTaskState.RETRY_WAIT
                        ? source.getErrorCode() : null);
                document.setFailureMessage(source.getState() == ProcessingTaskState.RETRY_WAIT
                        ? safe(source.getErrorMessage()) : null);
            }
            case SUCCESS -> {
                document.setStatus(KnowledgeLifecycleStatus.READY);
                document.setFailureCode(null);
                document.setFailureMessage(null);
            }
            case FAILED, DEAD, CANCELLED -> {
                document.setStatus(KnowledgeLifecycleStatus.FAILED);
                document.setFailureCode(source.getState() == ProcessingTaskState.CANCELLED
                        ? "TASK_CANCELLED" : source.getErrorCode());
                document.setFailureMessage(source.getState() == ProcessingTaskState.CANCELLED
                        ? "任务已取消" : safe(source.getErrorMessage()));
            }
        }
        document.setUpdatedTime(now);
        documents.updateById(document);
        refreshKnowledgeBase(document.getKnowledgeBaseId(), now);
    }

    private void refreshKnowledgeBase(Long knowledgeBaseId, LocalDateTime now) {
        long processing = documents.selectCount(Wrappers.<KnowledgeDocument>lambdaQuery()
                .eq(KnowledgeDocument::getKnowledgeBaseId, knowledgeBaseId)
                .eq(KnowledgeDocument::getActive, true)
                .in(KnowledgeDocument::getStatus, KnowledgeLifecycleStatus.UPLOADING,
                        KnowledgeLifecycleStatus.PROCESSING));
        long ready = documents.selectCount(Wrappers.<KnowledgeDocument>lambdaQuery()
                .eq(KnowledgeDocument::getKnowledgeBaseId, knowledgeBaseId)
                .eq(KnowledgeDocument::getActive, true)
                .eq(KnowledgeDocument::getStatus, KnowledgeLifecycleStatus.READY));
        long active = documents.selectCount(Wrappers.<KnowledgeDocument>lambdaQuery()
                .eq(KnowledgeDocument::getKnowledgeBaseId, knowledgeBaseId)
                .eq(KnowledgeDocument::getActive, true));
        KnowledgeBase base = knowledgeBases.selectById(knowledgeBaseId);
        if (base == null) return;
        base.setStatus(processing > 0 ? KnowledgeLifecycleStatus.PROCESSING
                : ready > 0 ? KnowledgeLifecycleStatus.READY
                : active > 0 ? KnowledgeLifecycleStatus.FAILED : KnowledgeLifecycleStatus.EMPTY);
        base.setUpdatedTime(now);
        knowledgeBases.updateById(base);
    }

    private static String safe(String message) {
        if (message == null) return null;
        String value = message.replaceAll("(?i)(api[_-]?key|authorization|bearer)\\s*[:=]?\\s*\\S+", "$1=***");
        return value.length() <= 1024 ? value : value.substring(0, 1024);
    }

    private static TaskStatus status(ProcessingTaskState state) {
        if (state == null) {
            throw new IllegalStateException("PROCESSING_TASK_STATE_MISSING");
        }
        return switch (state) {
            case PENDING -> TaskStatus.PENDING;
            case PROCESSING -> TaskStatus.PROCESSING;
            case RETRY_WAIT -> TaskStatus.RETRYING;
            case CANCEL_REQUESTED -> TaskStatus.CANCEL_REQUESTED;
            case CANCELLED -> TaskStatus.CANCELLED;
            case SUCCESS -> TaskStatus.SUCCESS;
            case FAILED, DEAD -> TaskStatus.FAILED;
        };
    }

    private static int value(Integer value) {
        return value == null ? 0 : value;
    }
}
