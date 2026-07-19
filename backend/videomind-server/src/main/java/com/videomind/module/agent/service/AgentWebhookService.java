package com.videomind.module.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.videomind.common.exception.BizException;
import com.videomind.module.agent.entity.VideoAgentTask;
import com.videomind.module.agent.mapper.VideoAgentTaskMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class AgentWebhookService {

    private final ObjectMapper objectMapper;
    private final VideoAgentTaskMapper agentTaskMapper;
    private final AgentTaskStateService stateService;

    public void handle(String body) {
        JsonNode event;
        try {
            event = objectMapper.readTree(body);
        } catch (Exception ex) {
            throw new BizException(400, "Agent Webhook JSON 无效");
        }
        String agentTaskId = text(event, "taskId", "agentTaskId");
        if (!StringUtils.hasText(agentTaskId)) {
            throw new BizException(400, "Agent Webhook 缺少 taskId");
        }
        VideoAgentTask task = agentTaskMapper.selectOne(new LambdaQueryWrapper<VideoAgentTask>()
                .eq(VideoAgentTask::getAgentTaskId, agentTaskId));
        if (task == null) {
            throw new BizException(404, "未找到对应的 Agent 任务");
        }

        stateService.applyWebhook(task, event);
    }

    private String text(JsonNode node, String... names) {
        for (String name : names) {
            if (node != null && node.hasNonNull(name)) {
                return node.get(name).asText();
            }
        }
        return null;
    }

}
