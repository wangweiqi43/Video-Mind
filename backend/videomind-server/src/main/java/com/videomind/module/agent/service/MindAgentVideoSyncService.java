package com.videomind.module.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.videomind.agentclient.AgentClientProperties;
import com.videomind.agentclient.AgentTaskClient;
import com.videomind.common.exception.BizException;
import com.videomind.infrastructure.storage.ObjectStorageService;
import com.videomind.infrastructure.storage.dto.StoredObject;
import com.videomind.module.agent.entity.VideoAgentTask;
import com.videomind.module.agent.mapper.VideoAgentTaskMapper;
import com.videomind.module.task.entity.TaskRecord;
import com.videomind.module.task.entity.VideoTranscription;
import com.videomind.module.task.mapper.VideoTranscriptionMapper;
import com.videomind.module.task.service.TaskRecordService;
import com.videomind.module.video.entity.VideoFile;
import com.videomind.module.video.service.VideoFileService;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class MindAgentVideoSyncService {

    private final VideoFileService videos;
    private final TaskRecordService tasks;
    private final VideoTranscriptionMapper transcripts;
    private final ObjectStorageService storage;
    private final AgentTaskClient client;
    private final AgentClientProperties properties;
    private final VideoAgentTaskMapper agentTasks;
    private final ObjectMapper objectMapper;

    public MindAgentVideoSyncService(VideoFileService videos, TaskRecordService tasks,
                                     VideoTranscriptionMapper transcripts, ObjectStorageService storage,
                                     AgentTaskClient client, AgentClientProperties properties,
                                     VideoAgentTaskMapper agentTasks, ObjectMapper objectMapper) {
        this.videos = videos;
        this.tasks = tasks;
        this.transcripts = transcripts;
        this.storage = storage;
        this.client = client;
        this.properties = properties;
        this.agentTasks = agentTasks;
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> sync(Long videoId, Long userId) {
        VideoFile video = videos.getVideoDetail(videoId, userId);
        int transcriptVersion = video.getTranscriptVersion() == null ? 0 : video.getTranscriptVersion();
        if (transcriptVersion < 1) throw new BizException(409, "视频尚未完成转录");
        if ("SUCCESS".equalsIgnoreCase(video.getAgentIngestStatus())
                && video.getAgentKnowledgeBaseId() != null) {
            return syncResponse(null, "SUCCESS", video.getAgentKnowledgeBaseId());
        }

        VideoAgentTask active = agentTasks.selectOne(new LambdaQueryWrapper<VideoAgentTask>()
                .eq(VideoAgentTask::getVideoId, videoId)
                .eq(VideoAgentTask::getUserId, userId)
                .eq(VideoAgentTask::getTaskType, "INGEST")
                .eq(VideoAgentTask::getVersion, transcriptVersion)
                .notIn(VideoAgentTask::getStatus, "FAILED", "CANCELLED")
                .orderByDesc(VideoAgentTask::getCreatedAt)
                .last("LIMIT 1"));
        if (active != null) {
            return syncResponse(active.getAgentTaskId(), active.getStatus(), video.getAgentKnowledgeBaseId());
        }

        TaskRecord task = tasks.getLatestSuccessfulTaskByVideo(videoId, userId);
        if (task == null) throw new BizException(409, "视频尚未完成转录");
        VideoTranscription transcript = transcripts.selectOne(new LambdaQueryWrapper<VideoTranscription>()
                .eq(VideoTranscription::getTaskId, task.getId())
                .eq(VideoTranscription::getUserId, userId));
        if (transcript == null) throw new BizException(409, "未找到视频转录文本");

        byte[] bytes = transcript.getTranscriptionText().getBytes(StandardCharsets.UTF_8);
        String objectKey = "agent-input/video-" + videoId + "/transcript-v" + transcriptVersion + ".txt";
        StoredObject object = storage.putObject(objectKey, new ByteArrayInputStream(bytes), bytes.length,
                "text/plain; charset=utf-8");
        String transcriptUrl = storage.presignGetUrl(object.getBucket(), object.getObjectKey(),
                Duration.ofSeconds(properties.getPresignedUrlExpirySeconds()));
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("filename", video.getOriginalFilename());
        metadata.put("fileMd5", video.getFileMd5());
        metadata.put("durationSeconds", video.getDurationSeconds());
        AgentTaskClient.AgentTaskResult result = client.ingest(new AgentTaskClient.AgentIngestRequest(
                        videoId, task.getId(), transcriptVersion, transcriptUrl, transcript.getLanguage(), true, metadata),
                userId, "ingest:video:" + videoId + ":transcript:" + transcriptVersion, null);

        VideoAgentTask agentTask = new VideoAgentTask();
        agentTask.setVideoId(videoId);
        agentTask.setUserId(userId);
        agentTask.setSourceTaskId(task.getId());
        agentTask.setAgentTaskId(result.taskId());
        agentTask.setTaskType("INGEST");
        agentTask.setStatus(result.status());
        agentTask.setProgress(0);
        agentTask.setVersion(transcriptVersion);
        agentTask.setCreatedAt(LocalDateTime.now());
        agentTask.setUpdatedAt(LocalDateTime.now());
        try {
            agentTask.setRequestJson(objectMapper.writeValueAsString(Map.of("transcriptObjectKey", objectKey)));
        } catch (Exception ignored) {
            // Request metadata is diagnostic only.
        }
        agentTasks.insert(agentTask);

        video.setAgentKnowledgeBaseId(result.knowledgeBaseId());
        video.setAgentIngestStatus(result.status());
        video.setSummaryStatus("PROCESSING");
        video.setAgentUpdatedAt(LocalDateTime.now());
        videos.updateById(video);
        return syncResponse(result.taskId(), result.status(), result.knowledgeBaseId());
    }

    private Map<String, Object> syncResponse(String taskId, String status, String knowledgeBaseId) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("taskId", taskId);
        response.put("status", status);
        response.put("knowledgeBaseId", knowledgeBaseId);
        return response;
    }
}
