package com.videomind.module.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.videomind.common.exception.BizException;
import com.videomind.module.agent.entity.VideoAgentTask;
import com.videomind.module.agent.mapper.VideoAgentTaskMapper;
import com.videomind.module.task.entity.AiSummaryResult;
import com.videomind.module.task.mapper.AiSummaryResultMapper;
import com.videomind.module.video.entity.VideoFile;
import com.videomind.module.video.service.VideoFileService;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class AgentWebhookService {

    private final ObjectMapper objectMapper;
    private final VideoAgentTaskMapper agentTaskMapper;
    private final VideoFileService videoFileService;
    private final AiSummaryResultMapper summaryMapper;

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

        JsonNode result = event.has("result") ? event.get("result") : event;
        String incomingStatus = textOrDefault(event, "status", task.getStatus());
        if (isTerminal(task.getStatus()) && !isTerminal(incomingStatus)) {
            return;
        }
        task.setStatus(incomingStatus);
        task.setProgress(event.hasNonNull("progress") ? event.get("progress").asInt() : task.getProgress());
        task.setErrorCode(text(event, "errorCode"));
        task.setErrorMessage(text(event, "errorMessage", "message"));
        task.setArtifactId(textOrDefault(result, "presentationId", textOrDefault(result, "artifactId", task.getArtifactId())));
        task.setOutputUrl(textOrDefault(result, "downloadUrl", textOrDefault(result, "outputUrl", task.getOutputUrl())));
        task.setUpdatedAt(LocalDateTime.now());
        agentTaskMapper.updateById(task);

        VideoFile video = videoFileService.getVideoDetail(task.getVideoId(), task.getUserId());
        applyVideoStatus(video, task, result);
        videoFileService.updateById(video);
    }

    private void applyVideoStatus(VideoFile video, VideoAgentTask task, JsonNode result) {
        if ("INGEST".equals(task.getTaskType())) {
            video.setAgentIngestStatus(task.getStatus());
            video.setAgentKnowledgeBaseId(textOrDefault(result, "knowledgeBaseId", video.getAgentKnowledgeBaseId()));
            String summaryText = text(result, "summaryText", "summary");
            if (StringUtils.hasText(summaryText)) {
                saveSummary(task, result, summaryText);
                String summaryId = text(result, "summaryId");
                boolean newSummaryVersion = StringUtils.hasText(summaryId)
                        ? !summaryId.equals(video.getLatestSummaryId())
                        : !"SUCCESS".equalsIgnoreCase(video.getSummaryStatus());
                video.setSummaryStatus("SUCCESS");
                if (newSummaryVersion) {
                    video.setSummaryVersion((video.getSummaryVersion() == null ? 0 : video.getSummaryVersion()) + 1);
                }
                video.setLatestSummaryId(textOrDefault(result, "summaryId", video.getLatestSummaryId()));
            } else if ("FAILED".equalsIgnoreCase(task.getStatus())) {
                video.setSummaryStatus("FAILED");
            }
        } else if ("PRESENTATION".equals(task.getTaskType()) && "SUCCESS".equalsIgnoreCase(task.getStatus())) {
            video.setLatestPresentationId(task.getArtifactId());
        }
        video.setAgentLastError("FAILED".equalsIgnoreCase(task.getStatus()) ? task.getErrorMessage() : null);
        video.setAgentUpdatedAt(LocalDateTime.now());
    }

    private void saveSummary(VideoAgentTask task, JsonNode result, String summaryText) {
        if (task.getSourceTaskId() == null) {
            return;
        }
        AiSummaryResult summary = summaryMapper.selectOne(new LambdaQueryWrapper<AiSummaryResult>()
                .eq(AiSummaryResult::getTaskId, task.getSourceTaskId()));
        if (summary == null) {
            summary = new AiSummaryResult();
            summary.setTaskId(task.getSourceTaskId());
            summary.setVideoId(task.getVideoId());
            summary.setUserId(task.getUserId());
            summary.setCreatedTime(LocalDateTime.now());
        }
        summary.setSummaryText(summaryText);
        JsonNode summaryJson = result.get("summaryJson");
        summary.setSummaryJson(summaryJson == null || summaryJson.isNull() ? null : summaryJson.toString());
        summary.setModelName(textOrDefault(result, "modelName", "agent-platform"));
        summary.setUpdatedTime(LocalDateTime.now());
        if (summary.getId() == null) {
            summaryMapper.insert(summary);
        } else {
            summaryMapper.updateById(summary);
        }
    }

    private String text(JsonNode node, String... names) {
        for (String name : names) {
            if (node != null && node.hasNonNull(name)) {
                return node.get(name).asText();
            }
        }
        return null;
    }

    private String textOrDefault(JsonNode node, String name, String defaultValue) {
        String value = text(node, name);
        return StringUtils.hasText(value) ? value : defaultValue;
    }

    private boolean isTerminal(String status) {
        return "SUCCESS".equalsIgnoreCase(status)
                || "COMPLETED".equalsIgnoreCase(status)
                || "FAILED".equalsIgnoreCase(status);
    }
}
