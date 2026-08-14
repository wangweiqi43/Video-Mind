package com.videomind.module.agent.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.videomind.agentclient.AgentClientProperties;
import com.videomind.agentclient.AgentTaskClient;
import com.videomind.module.agent.entity.VideoAgentTask;
import com.videomind.module.agent.mapper.VideoAgentTaskMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class AgentTaskCoordinatorTest {

    @Test
    void pollingAppliesRemoteSnapshotThroughSharedStateService() {
        AgentClientProperties properties = new AgentClientProperties();
        properties.setEnabled(true);
        properties.setIngestEnabled(true);
        AgentTaskClient client = mock(AgentTaskClient.class);
        AgentTaskStateService states = mock(AgentTaskStateService.class);
        VideoAgentTaskMapper tasks = mock(VideoAgentTaskMapper.class);
        VideoAgentTask task = new VideoAgentTask();
        task.setAgentTaskId("agent-task");
        task.setUserId(8L);
        task.setTaskType("INGEST");
        task.setStatus("RUNNING");
        AgentTaskClient.AgentTaskSnapshot snapshot = new AgentTaskClient.AgentTaskSnapshot(
                "agent-task", "SUCCESS", "COMPLETED", 100, null, null, "kb-1", null);
        when(tasks.selectList(any())).thenReturn(List.of(task));
        when(client.task("agent-task", 8L, null)).thenReturn(snapshot);

        new AgentTaskCoordinator(properties, client, states, tasks).pollIngestTasks();

        verify(states).applySnapshot(eq(task), eq(snapshot));
    }
}
