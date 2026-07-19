package com.videomind.module.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.videomind.agentclient.AgentClientProperties;
import com.videomind.agentclient.AgentTaskClient;
import com.videomind.common.exception.BizException;
import com.videomind.module.agent.dto.AdvancedReportResponse;
import com.videomind.module.agent.dto.AgentVideoSyncResponse;
import com.videomind.module.agent.entity.VideoAgentTask;
import com.videomind.module.agent.mapper.VideoAgentTaskMapper;
import com.videomind.module.video.entity.VideoFile;
import com.videomind.module.video.service.VideoFileService;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class AdvancedReportService {

    private static final String TASK_TYPE = "RESEARCH";
    private static final Pattern REFERENCE_SECTION_HEADING = Pattern.compile(
            "^[ \\t]{0,3}#{1,6}[ \\t]+(?:参考来源|引用来源|转录原文引用|references|sources)[ \\t]*[:：]?[ \\t]*\\r?$",
            Pattern.CASE_INSENSITIVE | Pattern.MULTILINE | Pattern.UNICODE_CASE);
    public static final String PROFILE = "VIDEOMIND_STUDY_NOTES_V1";

    private final AgentClientProperties properties;
    private final AgentTaskClient client;
    private final VideoAgentTaskMapper tasks;
    private final VideoFileService videos;
    private final MindAgentVideoSyncService syncService;
    private final ObjectMapper objectMapper;

    public AdvancedReportResponse ensure(Long videoId, Long userId) {
        return ensure(videoId, userId, null);
    }

    public AdvancedReportResponse ensure(Long videoId, Long userId, Long sourceTaskId) {
        requireEnabled();
        VideoFile video = videos.getVideoDetail(videoId, userId);
        int transcriptVersion = transcriptVersion(video);
        VideoAgentTask existing = latest(videoId, userId, transcriptVersion);
        if (existing != null && !isRetryableTerminal(existing.getStatus())) {
            return response(existing, true);
        }

        if (!"SUCCESS".equalsIgnoreCase(video.getAgentIngestStatus())
                || !StringUtils.hasText(video.getAgentSourceKnowledgeBaseId())) {
            AgentVideoSyncResponse sync = syncService.sync(videoId, userId, sourceTaskId);
            String status = sync.getStatus();
            return AdvancedReportResponse.builder()
                    .videoId(videoId)
                    .status(isSuccess(status) ? "READY" : "SYNCING")
                    .progress(isSuccess(status) ? 10 : 5)
                    .stage("SYNC_TRANSCRIPT")
                    .transcriptVersion(transcriptVersion)
                    .targetLength(targetLength(video.getDurationSeconds()))
                    .build();
        }

        int attempt = attempts(videoId, userId, transcriptVersion) + 1;
        int targetLength = targetLength(video.getDurationSeconds());
        if (existing != null && isRetryableTerminal(existing.getStatus())) {
            AgentTaskClient.AgentTaskResult retried = client.retry(existing.getAgentTaskId(), userId,
                    "retry:advanced-summary:" + existing.getAgentTaskId() + ":attempt:" + attempt, null);
            VideoAgentTask retry = new VideoAgentTask();retry.setVideoId(videoId);retry.setUserId(userId);retry.setSourceTaskId(sourceTaskId);
            retry.setAgentTaskId(retried.taskId());retry.setTaskType(TASK_TYPE);retry.setStatus(retried.status());retry.setProgress(10);
            retry.setArtifactId(retried.artifactId());retry.setOutputUrl(retried.downloadUrl());retry.setVersion(transcriptVersion);retry.setProfileVersion(PROFILE);
            retry.setRequestJson(json(Map.of("attempt",attempt,"targetLength",targetLength,"transcriptVersion",transcriptVersion,"webSearch",false,"retriedTaskId",existing.getAgentTaskId())));
            retry.setCreatedAt(LocalDateTime.now());retry.setUpdatedAt(LocalDateTime.now());tasks.insert(retry);
            video.setAgentReportStatus(retry.getStatus());video.setAgentReportVersion(transcriptVersion);video.setAgentReportProfile(PROFILE);video.setAgentUpdatedAt(LocalDateTime.now());videos.updateById(video);
            return response(retry,false);
        }
        String question = "基于视频《" + safeTitle(video.getOriginalFilename())
                + "》的完整转录内容生成高级摘要总结：先给核心概览，再按原顺序整理接近逐字的完整学习笔记；保留所有数字、页码、章节、分值、人名、理论、定义、步骤、例子、否定、条件、例外和不确定表达。";
        AgentTaskClient.AgentTaskResult result = client.createResearch(
                videoId,
                video.getAgentSourceKnowledgeBaseId(),
                question,
                false,
                targetLength,
                transcriptVersion,
                safeTitle(video.getOriginalFilename()),
                userId,
                "advanced-report:video:" + videoId + ":transcript:" + transcriptVersion + ":attempt:" + attempt,
                null,
                PROFILE
        );
        VideoAgentTask task = new VideoAgentTask();
        task.setVideoId(videoId);
        task.setUserId(userId);
        task.setSourceTaskId(sourceTaskId);
        task.setAgentTaskId(result.taskId());
        task.setTaskType(TASK_TYPE);
        task.setStatus(result.status());
        task.setProgress(10);
        task.setArtifactId(result.artifactId());
        task.setOutputUrl(result.downloadUrl());
        task.setVersion(transcriptVersion);
        task.setProfileVersion(PROFILE);
        task.setRequestJson(json(Map.of(
                "attempt", attempt,
                "targetLength", targetLength,
                "transcriptVersion", transcriptVersion,
                "webSearch", false
        )));
        task.setCreatedAt(LocalDateTime.now());
        task.setUpdatedAt(LocalDateTime.now());
        try {
            tasks.insert(task);
            video.setAgentReportStatus(task.getStatus());
            video.setAgentReportVersion(transcriptVersion);
            video.setAgentReportProfile(PROFILE);
            video.setAgentUpdatedAt(LocalDateTime.now());
            videos.updateById(video);
            return response(task, false);
        } catch (DuplicateKeyException duplicate) {
            VideoAgentTask concurrent = latest(videoId, userId, transcriptVersion);
            if (concurrent != null) return response(concurrent, true);
            throw duplicate;
        }
    }

    public AdvancedReportResponse detail(Long videoId, Long userId) {
        requireEnabled();
        VideoFile video = videos.getVideoDetail(videoId, userId);
        int transcriptVersion = transcriptVersion(video);
        VideoAgentTask task = latest(videoId, userId, transcriptVersion);
        if (task == null) {
            return AdvancedReportResponse.builder()
                    .videoId(videoId)
                    .status("NOT_STARTED")
                    .progress(0)
                    .stage("WAITING")
                    .transcriptVersion(transcriptVersion)
                    .targetLength(targetLength(video.getDurationSeconds()))
                    .build();
        }
        return response(task, true);
    }

    private AdvancedReportResponse response(VideoAgentTask task, boolean includeReport) {
        AdvancedReportResponse.AdvancedReportResponseBuilder builder = AdvancedReportResponse.builder()
                .id(task.getId())
                .videoId(task.getVideoId())
                .agentTaskId(task.getAgentTaskId())
                .status(task.getStatus())
                .progress(task.getProgress())
                .stage(StringUtils.hasText(task.getStage()) ? task.getStage() : stage(task.getStatus()))
                .errorCode(task.getErrorCode())
                .errorMessage(task.getErrorMessage())
                .reportId(task.getReportId())
                .reportKnowledgeBaseId(videos.getVideoDetail(task.getVideoId(), task.getUserId()).getAgentReportKnowledgeBaseId())
                .artifactId(task.getArtifactId())
                .downloadUrl(StringUtils.hasText(task.getReportId())
                        ? "/api/videos/" + task.getVideoId() + "/advanced-report/download"
                        : null)
                .transcriptVersion(task.getVersion())
                .targetLength(requestInt(task, "targetLength", 1500))
                .outputProfile(StringUtils.hasText(task.getProfileVersion()) ? task.getProfileVersion() : "LIGHT_RESEARCH_V1")
                .createdAt(task.getCreatedAt())
                .updatedAt(task.getUpdatedAt());
        if (includeReport && isSuccess(task.getStatus()) && StringUtils.hasText(task.getReportId())) {
            JsonNode report = client.researchReport(task.getReportId(), task.getUserId());
            builder.reportMarkdown(stripReferenceSection(report.path("report").asText(null)))
                    .downloadUrl("/api/videos/" + task.getVideoId() + "/advanced-report/download")
                    .targetLength(report.path("targetLength").asInt(requestInt(task, "targetLength", 1500)));
        }
        return builder.build();
    }

    static String stripReferenceSection(String markdown) {
        if (!StringUtils.hasText(markdown)) {
            return markdown;
        }
        Matcher matcher = REFERENCE_SECTION_HEADING.matcher(markdown);
        return matcher.find() ? markdown.substring(0, matcher.start()).stripTrailing() : markdown;
    }

    private VideoAgentTask latest(Long videoId, Long userId, int transcriptVersion) {
        return tasks.selectOne(new LambdaQueryWrapper<VideoAgentTask>()
                .eq(VideoAgentTask::getVideoId, videoId)
                .eq(VideoAgentTask::getUserId, userId)
                .eq(VideoAgentTask::getTaskType, TASK_TYPE)
                .eq(VideoAgentTask::getVersion, transcriptVersion)
                .eq(VideoAgentTask::getProfileVersion, PROFILE)
                .orderByDesc(VideoAgentTask::getCreatedAt)
                .last("LIMIT 1"));
    }

    private int attempts(Long videoId, Long userId, int transcriptVersion) {
        return Math.toIntExact(tasks.selectCount(new LambdaQueryWrapper<VideoAgentTask>()
                .eq(VideoAgentTask::getVideoId, videoId)
                .eq(VideoAgentTask::getUserId, userId)
                .eq(VideoAgentTask::getTaskType, TASK_TYPE)
                .eq(VideoAgentTask::getVersion, transcriptVersion)));
    }

    static int targetLength(Integer durationSeconds) {
        int seconds = durationSeconds == null ? 0 : durationSeconds;
        if (seconds <= 600) return 1000;
        if (seconds <= 1800) return 1200;
        if (seconds <= 3600) return 1500;
        if (seconds <= 7200) return 1800;
        return 2000;
    }

    private int transcriptVersion(VideoFile video) {
        if (video.getTranscriptVersion() == null || video.getTranscriptVersion() < 1) {
            throw new BizException(409, "视频尚未完成转录，无法生成高级摘要总结");
        }
        return video.getTranscriptVersion();
    }

    private void requireEnabled() {
        if (!properties.isEnabled() || !properties.isAdvancedReportEnabled()) {
            throw new BizException(503, "高级摘要总结尚未启用");
        }
    }

    private boolean isRetryableTerminal(String status) {
        return "FAILED".equalsIgnoreCase(status) || "CANCELLED".equalsIgnoreCase(status);
    }

    private boolean isSuccess(String status) {
        return "SUCCESS".equalsIgnoreCase(status) || "COMPLETED".equalsIgnoreCase(status);
    }

    private String stage(String status) {
        if (isSuccess(status)) return "COMPLETED";
        if (isRetryableTerminal(status)) return "FAILED";
        return "RESEARCHING";
    }

    private int requestInt(VideoAgentTask task, String name, int fallback) {
        try {
            return objectMapper.readTree(task.getRequestJson()).path(name).asInt(fallback);
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException("无法保存高级摘要请求", ex);
        }
    }

    private String safeTitle(String filename) {
        if (!StringUtils.hasText(filename)) return "当前视频";
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
    }
}
