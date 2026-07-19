package com.videomind.module.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.videomind.agentclient.AgentClientException;
import com.videomind.agentclient.AgentClientProperties;
import com.videomind.agentclient.AgentTaskClient;
import com.videomind.module.agent.entity.VideoAgentTask;
import com.videomind.module.agent.mapper.VideoAgentTaskMapper;
import com.videomind.module.agent.dto.AgentVideoSyncResponse;
import com.videomind.module.agent.dto.AdvancedReportResponse;
import com.videomind.module.task.entity.TaskRecord;
import com.videomind.module.task.service.TaskRecordService;
import com.videomind.common.enums.TaskStatus;
import java.util.List;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class AgentTaskCoordinator {

    private final AgentClientProperties properties;
    private final AgentTaskClient client;
    private final AgentTaskStateService stateService;
    private final VideoAgentTaskMapper tasks;
    private final TaskRecordService taskRecords;
    private final MindAgentVideoSyncService syncService;
    private final AdvancedReportService reportService;

    @Scheduled(fixedDelayString = "${videomind.agent.task-poll-interval-seconds:5}", timeUnit = TimeUnit.SECONDS)
    public void pollIngestTasks() {
        if (!properties.isEnabled() || !properties.isIngestEnabled()) return;
        List<VideoAgentTask> running = tasks.selectList(new LambdaQueryWrapper<VideoAgentTask>()
                .in(VideoAgentTask::getTaskType, "INGEST", "RESEARCH")
                .in(VideoAgentTask::getStatus, "PENDING", "RUNNING")
                .orderByAsc(VideoAgentTask::getUpdatedAt)
                .last("LIMIT 50"));
        for (VideoAgentTask task : running) {
            if (task.getAgentTaskId().startsWith("already-indexed:")) continue;
            try {
                stateService.applySnapshot(task,
                        client.task(task.getAgentTaskId(), task.getUserId(), null));
            } catch (AgentClientException failure) {
                log.warn("Agent ingest poll deferred: taskId={}, code={}, status={}, retryable={}",
                        task.getAgentTaskId(), failure.getErrorCode(), failure.getHttpStatus(), failure.isRetryable());
            } catch (RuntimeException failure) {
                log.warn("Agent ingest poll failed: taskId={}, type={}",
                        task.getAgentTaskId(), failure.getClass().getSimpleName());
            }
        }
    }

    @Scheduled(fixedDelayString = "${videomind.agent.task-poll-interval-seconds:5}", timeUnit = TimeUnit.SECONDS)
    public void advanceExplicitAdvancedAnalyses() {
        if (!properties.isEnabled() || !properties.isIngestEnabled() || !properties.isAdvancedReportEnabled()) return;
        List<TaskRecord> running = taskRecords.list(new LambdaQueryWrapper<TaskRecord>()
                .eq(TaskRecord::getAnalysisMode, "ADVANCED")
                .in(TaskRecord::getTaskStatus, TaskStatus.PROCESSING, TaskStatus.RETRYING)
                .orderByAsc(TaskRecord::getUpdatedTime)
                .last("LIMIT 50"));
        for (TaskRecord source : running) {
            try {
                AgentVideoSyncResponse ingest = syncService.status(source.getVideoId(), source.getUserId());
                String ingestStatus = String.valueOf(ingest.getStatus()).toUpperCase();
                if ("UNSYNCED".equals(ingestStatus)) {
                    syncService.sync(source.getVideoId(), source.getUserId(), source.getId());
                    continue;
                }
                if ("FAILED".equals(ingestStatus) || "CANCELLED".equals(ingestStatus)) {
                    taskRecords.markFailed(source.getId(), source.getUserId(), ingest.getErrorMessage());
                    continue;
                }
                if (!"SUCCESS".equals(ingestStatus)) continue;
                AdvancedReportResponse report = reportService.ensure(source.getVideoId(), source.getUserId(), source.getId());
                if ("SUCCESS".equalsIgnoreCase(report.getStatus()) || "COMPLETED".equalsIgnoreCase(report.getStatus())) {
                    taskRecords.markSuccess(source.getId(), source.getUserId());
                } else if ("FAILED".equalsIgnoreCase(report.getStatus()) || "CANCELLED".equalsIgnoreCase(report.getStatus())) {
                    taskRecords.markFailed(source.getId(), source.getUserId(), report.getErrorMessage());
                }
            } catch (RuntimeException failure) {
                log.warn("Advanced analysis coordination deferred: taskId={}, type={}",
                        source.getId(), failure.getClass().getSimpleName());
            }
        }
    }
}
