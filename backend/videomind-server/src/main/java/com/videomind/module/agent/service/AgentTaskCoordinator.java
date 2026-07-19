package com.videomind.module.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.videomind.agentclient.AgentClientException;
import com.videomind.agentclient.AgentClientProperties;
import com.videomind.agentclient.AgentTaskClient;
import com.videomind.module.agent.entity.VideoAgentTask;
import com.videomind.module.agent.mapper.VideoAgentTaskMapper;
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

    @Scheduled(fixedDelayString = "${videomind.agent.task-poll-interval-seconds:5}", timeUnit = TimeUnit.SECONDS)
    public void pollIngestTasks() {
        if (!properties.isEnabled() || !properties.isIngestEnabled()) return;
        List<VideoAgentTask> running = tasks.selectList(new LambdaQueryWrapper<VideoAgentTask>()
                .eq(VideoAgentTask::getTaskType, "INGEST")
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
}
