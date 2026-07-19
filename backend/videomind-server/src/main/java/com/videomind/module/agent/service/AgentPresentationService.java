package com.videomind.module.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.videomind.agentclient.AgentClientProperties;
import com.videomind.agentclient.AgentTaskClient;
import com.videomind.common.exception.BizException;
import com.videomind.module.agent.dto.PresentationCreateRequest;
import com.videomind.module.agent.dto.PresentationTaskResponse;
import com.videomind.module.agent.entity.VideoAgentTask;
import com.videomind.module.agent.mapper.VideoAgentTaskMapper;
import com.videomind.module.video.entity.VideoFile;
import com.videomind.module.video.service.VideoFileService;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AgentPresentationService {

    private final AgentClientProperties properties;
    private final AgentTaskClient taskClient;
    private final VideoAgentTaskMapper taskMapper;
    private final VideoFileService videoFileService;
    private final ObjectMapper objectMapper;

    public PresentationTaskResponse create(Long videoId, PresentationCreateRequest request, Long userId) {
        if (!properties.isEnabled() || !properties.isPresentationEnabled()) {
            throw new BizException(503, "Agent Platform PPT 生成功能尚未启用");
        }
        VideoFile video = videoFileService.getVideoDetail(videoId, userId);
        if (video.getAgentReportKnowledgeBaseId() == null) {
            throw new BizException(409, "视频深度研究报告知识库尚未就绪，暂时不能生成 PPT");
        }
        int version = nextVersion(videoId, userId);
        AgentTaskClient.PresentationOptions options = toOptions(request);
        AgentTaskClient.AgentTaskResult result = taskClient.createPresentation(
                videoId,
                video.getAgentReportKnowledgeBaseId(),
                options,
                userId,
                "presentation:video:" + videoId + ":version:" + version,
                null
        );
        VideoAgentTask task = new VideoAgentTask();
        task.setVideoId(videoId);
        task.setUserId(userId);
        task.setAgentTaskId(result.taskId());
        task.setTaskType("PRESENTATION");
        task.setStatus(result.status());
        task.setProgress(0);
        task.setArtifactId(result.artifactId());
        task.setOutputUrl(result.downloadUrl());
        task.setVersion(version);
        task.setRequestJson(writeJson(options));
        task.setCreatedAt(LocalDateTime.now());
        task.setUpdatedAt(LocalDateTime.now());
        taskMapper.insert(task);
        return toResponse(task);
    }

    public PresentationTaskResponse retry(Long videoId, Long taskId, Long userId) {
        VideoAgentTask previous = getTask(videoId, taskId, userId);
        PresentationCreateRequest request;
        try {
            AgentTaskClient.PresentationOptions options = objectMapper.readValue(
                    previous.getRequestJson(), AgentTaskClient.PresentationOptions.class);
            request = new PresentationCreateRequest();
            request.setTemplate(options.template());
            request.setLanguage(options.language());
            request.setSlideCount(options.slideCount());
            request.setAudience(options.audience());
            request.setTone(options.tone());
        } catch (Exception ex) {
            request = new PresentationCreateRequest();
        }
        return create(videoId, request, userId);
    }

    public List<PresentationTaskResponse> list(Long videoId, Long userId) {
        videoFileService.getVideoDetail(videoId, userId);
        return taskMapper.selectList(new LambdaQueryWrapper<VideoAgentTask>()
                        .eq(VideoAgentTask::getVideoId, videoId)
                        .eq(VideoAgentTask::getUserId, userId)
                        .eq(VideoAgentTask::getTaskType, "PRESENTATION")
                        .orderByDesc(VideoAgentTask::getVersion))
                .stream().map(this::toResponse).toList();
    }

    public PresentationTaskResponse detail(Long videoId, Long taskId, Long userId) {
        return toResponse(getTask(videoId, taskId, userId));
    }

    private VideoAgentTask getTask(Long videoId, Long taskId, Long userId) {
        VideoAgentTask task = taskMapper.selectOne(new LambdaQueryWrapper<VideoAgentTask>()
                .eq(VideoAgentTask::getId, taskId)
                .eq(VideoAgentTask::getVideoId, videoId)
                .eq(VideoAgentTask::getUserId, userId)
                .eq(VideoAgentTask::getTaskType, "PRESENTATION"));
        if (task == null) {
            throw new BizException(404, "PPT 任务不存在或无权访问");
        }
        return task;
    }

    private int nextVersion(Long videoId, Long userId) {
        VideoAgentTask latest = taskMapper.selectOne(new LambdaQueryWrapper<VideoAgentTask>()
                .eq(VideoAgentTask::getVideoId, videoId)
                .eq(VideoAgentTask::getUserId, userId)
                .eq(VideoAgentTask::getTaskType, "PRESENTATION")
                .orderByDesc(VideoAgentTask::getVersion)
                .last("LIMIT 1"));
        return latest == null || latest.getVersion() == null ? 1 : latest.getVersion() + 1;
    }

    private AgentTaskClient.PresentationOptions toOptions(PresentationCreateRequest request) {
        return new AgentTaskClient.PresentationOptions(
                request.getTemplate(), request.getLanguage(), request.getSlideCount(), request.getAudience(), request.getTone());
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new BizException(500, "保存 PPT 参数失败：" + ex.getMessage());
        }
    }

    private PresentationTaskResponse toResponse(VideoAgentTask task) {
        return PresentationTaskResponse.builder()
                .id(task.getId())
                .videoId(task.getVideoId())
                .agentTaskId(task.getAgentTaskId())
                .status(task.getStatus())
                .progress(task.getProgress())
                .errorCode(task.getErrorCode())
                .errorMessage(task.getErrorMessage())
                .presentationId(task.getArtifactId())
                .downloadUrl(task.getOutputUrl())
                .version(task.getVersion())
                .createdAt(task.getCreatedAt())
                .updatedAt(task.getUpdatedAt())
                .build();
    }
}
