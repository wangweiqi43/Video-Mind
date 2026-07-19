package com.videomind.module.agent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.videomind.agentclient.AgentTaskClient;
import com.videomind.module.agent.entity.VideoAgentTask;
import com.videomind.module.agent.mapper.VideoAgentTaskMapper;
import com.videomind.module.video.entity.VideoFile;
import com.videomind.module.video.service.VideoFileService;
import java.time.LocalDateTime;
import java.util.Locale;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class AgentTaskStateService {

    private final VideoAgentTaskMapper tasks;
    private final VideoFileService videos;

    public AgentTaskStateService(VideoAgentTaskMapper tasks, VideoFileService videos) {
        this.tasks = tasks;
        this.videos = videos;
    }

    @Transactional
    public void applyWebhook(VideoAgentTask task, JsonNode event) {
        JsonNode result = event.has("result") ? event.get("result") : event;
        apply(task,
                text(event, "status"),
                text(event, "stage"),
                event.hasNonNull("progress") ? event.get("progress").asInt() : task.getProgress(),
                text(event, "errorCode"),
                text(event, "errorMessage", "message"),
                text(result, "knowledgeBaseId"),
                result);
    }

    @Transactional
    public void applySnapshot(VideoAgentTask task, AgentTaskClient.AgentTaskSnapshot snapshot) {
        apply(task, snapshot.status(), snapshot.stage(), snapshot.progress(), snapshot.errorCode(),
                snapshot.errorMessage(), snapshot.knowledgeBaseId(), snapshot.result());
    }

    private void apply(VideoAgentTask task, String rawStatus, String stage, Integer progress,
                       String errorCode, String errorMessage, String knowledgeBaseId, JsonNode result) {
        String incoming = normalizeStatus(rawStatus);
        if (isTerminal(task.getStatus())) {
            return;
        }
        if ("SUCCESS".equals(incoming) && "INGEST".equals(task.getTaskType())
                && !StringUtils.hasText(knowledgeBaseId)) {
            incoming = "FAILED";
            errorCode = "INVALID_RESPONSE";
            errorMessage = "Agent Platform 入库成功响应缺少 knowledgeBaseId";
        }

        task.setStatus(incoming);
        task.setStage(StringUtils.hasText(stage) ? stage : terminalStage(incoming));
        task.setProgress(Math.max(0, Math.min(100, progress == null ? 0 : progress)));
        task.setErrorCode(cut(errorCode, 128));
        task.setErrorMessage(cut(errorMessage, 1000));
        if (result != null) {
            task.setArtifactId(firstText(result, task.getArtifactId(), "presentationId", "artifactId"));
            if ("RESEARCH".equals(task.getTaskType())) {
                task.setReportId(firstText(result, task.getReportId(), "reportId"));
            }
            task.setOutputUrl(firstText(result, task.getOutputUrl(), "downloadUrl", "outputUrl"));
        }
        task.setUpdatedAt(LocalDateTime.now());
        tasks.updateById(task);

        VideoFile video = videos.getVideoDetail(task.getVideoId(), task.getUserId());
        if ("INGEST".equals(task.getTaskType())) {
            video.setAgentIngestStatus(incoming);
            if ("SUCCESS".equals(incoming)) {
                video.setAgentIngestVersion(task.getVersion());
                video.setAgentSourceKnowledgeBaseId(knowledgeBaseId);
                video.setAgentLastError(null);
            } else if ("FAILED".equals(incoming) || "CANCELLED".equals(incoming)) {
                video.setAgentLastError(task.getErrorMessage());
            }
        } else if ("RESEARCH".equals(task.getTaskType()) && "SUCCESS".equals(incoming)) {
            String reportKnowledgeBaseId=text(result,"reportKnowledgeBaseId");
            if(!StringUtils.hasText(task.getReportId())||!StringUtils.hasText(reportKnowledgeBaseId)){
                task.setStatus("FAILED");task.setStage("FAILED");task.setErrorCode("INVALID_RESPONSE");task.setErrorMessage("Agent Platform 研究成功响应缺少报告或报告知识库 ID");tasks.updateById(task);
                video.setAgentLastError(task.getErrorMessage());
            }else{video.setAgentReportKnowledgeBaseId(reportKnowledgeBaseId);video.setAgentLastError(null);}
        } else if ("PRESENTATION".equals(task.getTaskType()) && "SUCCESS".equals(incoming)) {
            video.setLatestPresentationId(task.getArtifactId());
        }
        video.setAgentUpdatedAt(LocalDateTime.now());
        videos.updateById(video);
    }

    public String normalizeStatus(String value) {
        String status = value == null ? "PENDING" : value.trim().toUpperCase(Locale.ROOT);
        return switch (status) {
            case "QUEUED", "PENDING" -> "PENDING";
            case "PROCESSING", "RUNNING" -> "RUNNING";
            case "COMPLETED", "SUCCESS" -> "SUCCESS";
            case "FAILED" -> "FAILED";
            case "CANCELLED", "CANCELED" -> "CANCELLED";
            default -> "RUNNING";
        };
    }

    public boolean isTerminal(String status) {
        return "SUCCESS".equalsIgnoreCase(status)
                || "FAILED".equalsIgnoreCase(status)
                || "CANCELLED".equalsIgnoreCase(status);
    }

    private String terminalStage(String status) {
        return isTerminal(status) ? status : null;
    }

    private String firstText(JsonNode node, String fallback, String... names) {
        String value = text(node, names);
        return StringUtils.hasText(value) ? value : fallback;
    }

    private String text(JsonNode node, String... names) {
        if (node == null) return null;
        for (String name : names) {
            if (node.hasNonNull(name)) return node.get(name).asText();
        }
        return null;
    }

    private String cut(String value, int max) {
        if (!StringUtils.hasText(value)) return null;
        String safe = value.replaceAll("[\\r\\n\\t]+", " ").trim();
        return safe.substring(0, Math.min(max, safe.length()));
    }
}
