package com.videomind.module.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.videomind.agentclient.AgentClientProperties;
import com.videomind.agentclient.AgentClientException;
import com.videomind.agentclient.AgentTaskClient;
import com.videomind.common.exception.BizException;
import com.videomind.infrastructure.storage.ObjectStorageService;
import com.videomind.infrastructure.storage.dto.StoredObject;
import com.videomind.module.agent.entity.VideoAgentTask;
import com.videomind.module.agent.dto.AgentVideoSyncResponse;
import com.videomind.module.agent.mapper.VideoAgentTaskMapper;
import com.videomind.module.task.entity.VideoTranscription;
import com.videomind.module.task.mapper.VideoTranscriptionMapper;
import com.videomind.module.video.entity.VideoFile;
import com.videomind.module.video.service.VideoFileService;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class MindAgentVideoSyncService {

    private final VideoFileService videos;
    private final VideoTranscriptionMapper transcripts;
    private final ObjectStorageService storage;
    private final AgentTaskClient client;
    private final AgentClientProperties properties;
    private final VideoAgentTaskMapper agentTasks;
    private final ObjectMapper objectMapper;
    private final RedissonClient redisson;
    private final AgentTaskStateService stateService;

    public MindAgentVideoSyncService(VideoFileService videos,
                                     VideoTranscriptionMapper transcripts, ObjectStorageService storage,
                                     AgentTaskClient client, AgentClientProperties properties,
                                     VideoAgentTaskMapper agentTasks, ObjectMapper objectMapper,
                                     RedissonClient redisson, AgentTaskStateService stateService) {
        this.videos = videos;
        this.transcripts = transcripts;
        this.storage = storage;
        this.client = client;
        this.properties = properties;
        this.agentTasks = agentTasks;
        this.objectMapper = objectMapper;
        this.redisson = redisson;
        this.stateService = stateService;
    }

    public AgentVideoSyncResponse sync(Long videoId, Long userId) {
        return sync(videoId, userId, null);
    }

    public AgentVideoSyncResponse sync(Long videoId, Long userId, Long sourceTaskId) {
        requireEnabled();
        VideoFile video = requireTranscript(videoId, userId);
        int version = video.getTranscriptVersion();
        RLock lock = redisson.getLock("lock:agent:ingest:user:" + userId + ":video:" + videoId + ":version:" + version);
        boolean locked = false;
        try {
            locked = lock.tryLock(5, TimeUnit.SECONDS);
            if (!locked) {
                throw new BizException(409, "该视频正在同步，请稍后查询进度");
            }
            video = requireTranscript(videoId, userId);
            version = video.getTranscriptVersion();
            VideoAgentTask latest = latest(videoId, userId, version);
            if (latest != null && "SUCCESS".equalsIgnoreCase(latest.getStatus())
                    && Integer.valueOf(version).equals(video.getAgentIngestVersion())
                    && StringUtils.hasText(video.getAgentSourceKnowledgeBaseId())) {
                return response(video, latest);
            }
            if (latest != null && !stateService.isTerminal(latest.getStatus())) {
                reconcileBestEffort(latest);
                return response(videos.getVideoDetail(videoId, userId), latest);
            }
            if (latest != null && ("FAILED".equalsIgnoreCase(latest.getStatus())
                    || "CANCELLED".equalsIgnoreCase(latest.getStatus()))) {
                return retry(video, latest);
            }
            return create(video, userId, version, sourceTaskId);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new BizException(503, "同步锁等待被中断，请稍后重试");
        } finally {
            if (locked && lock.isHeldByCurrentThread()) lock.unlock();
        }
    }

    public AgentVideoSyncResponse status(Long videoId, Long userId) {
        VideoFile video = videos.getVideoDetail(videoId, userId);
        int version = video.getTranscriptVersion() == null ? 0 : video.getTranscriptVersion();
        if (version < 1) return unsynced(videoId, version);
        VideoAgentTask latest = latest(videoId, userId, version);
        return latest == null ? unsynced(videoId, version) : response(video, latest);
    }

    private AgentVideoSyncResponse create(VideoFile video, Long userId, int version, Long sourceTaskId) {
        VideoTranscription transcript = transcripts.selectOne(new LambdaQueryWrapper<VideoTranscription>()
                .eq(VideoTranscription::getVideoId, video.getId())
                .eq(VideoTranscription::getUserId, userId)
                .orderByDesc(VideoTranscription::getUpdatedTime)
                .last("LIMIT 1"));
        if (transcript == null || !StringUtils.hasText(transcript.getTranscriptionText())) {
            throw new BizException(409, "未找到视频转录文本");
        }

        byte[] bytes = transcript.getTranscriptionText().getBytes(StandardCharsets.UTF_8);
        String objectKey = "agent-input/video-" + video.getId() + "/transcript-v" + version + ".txt";
        StoredObject object = storage.putObject(objectKey, new ByteArrayInputStream(bytes), bytes.length,
                "text/plain; charset=utf-8");
        String transcriptUrl = storage.presignGetUrl(object.getBucket(), object.getObjectKey(),
                Duration.ofSeconds(properties.getPresignedUrlExpirySeconds()));
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("filename", video.getOriginalFilename());
        metadata.put("fileMd5", video.getFileMd5());
        metadata.put("durationSeconds", video.getDurationSeconds());
        long effectiveSourceTaskId = sourceTaskId == null ? transcript.getTaskId() : sourceTaskId;
        AgentTaskClient.AgentTaskResult result = client.ingest(new AgentTaskClient.AgentIngestRequest(
                        video.getId(), effectiveSourceTaskId, version, transcriptUrl, transcript.getLanguage(), true, metadata),
                userId, "ingest:video:" + video.getId() + ":transcript:" + version, null);
        VideoAgentTask local = saveTask(video, effectiveSourceTaskId, version, result,
                Map.of("transcriptObjectKey", objectKey, "attempt", 1));
        applyInitial(video, local, result.knowledgeBaseId());
        return response(video, local);
    }

    private AgentVideoSyncResponse retry(VideoFile video, VideoAgentTask failed) {
        long attempts = agentTasks.selectCount(new LambdaQueryWrapper<VideoAgentTask>()
                .eq(VideoAgentTask::getVideoId, video.getId())
                .eq(VideoAgentTask::getUserId, failed.getUserId())
                .eq(VideoAgentTask::getTaskType, "INGEST")
                .eq(VideoAgentTask::getVersion, failed.getVersion()));
        AgentTaskClient.AgentTaskResult result = client.retry(failed.getAgentTaskId(), failed.getUserId(),
                "retry:ingest:" + failed.getAgentTaskId() + ":attempt:" + (attempts + 1), null);
        Map<String, Object> diagnostic = new LinkedHashMap<>();
        diagnostic.put("retriedTaskId", failed.getAgentTaskId());
        diagnostic.put("attempt", attempts + 1);
        VideoAgentTask local = saveTask(video, failed.getSourceTaskId(), failed.getVersion(), result, diagnostic);
        applyInitial(video, local, result.knowledgeBaseId());
        return response(video, local);
    }

    private VideoAgentTask saveTask(VideoFile video, Long sourceTaskId, int version,
                                    AgentTaskClient.AgentTaskResult result, Map<String, Object> diagnostic) {
        VideoAgentTask task = new VideoAgentTask();
        task.setVideoId(video.getId());
        task.setUserId(video.getUserId());
        task.setSourceTaskId(sourceTaskId);
        String taskId = result.taskId();
        if ("already-indexed".equals(taskId)) {
            taskId = "already-indexed:" + video.getUserId() + ":" + video.getId() + ":" + version;
        }
        task.setAgentTaskId(taskId);
        task.setTaskType("INGEST");
        task.setStatus(stateService.normalizeStatus(result.status()));
        task.setStage("SUCCESS".equals(task.getStatus()) ? "SUCCESS" : "QUEUED");
        task.setProgress("SUCCESS".equals(task.getStatus()) ? 100 : 0);
        task.setVersion(version);
        task.setCreatedAt(LocalDateTime.now());
        task.setUpdatedAt(LocalDateTime.now());
        try {
            task.setRequestJson(objectMapper.writeValueAsString(diagnostic));
        } catch (Exception ignored) {
            // Diagnostic metadata never contains the pre-signed URL or credentials.
        }
        agentTasks.insert(task);
        return task;
    }

    private void applyInitial(VideoFile video, VideoAgentTask task, String knowledgeBaseId) {
        if ("SUCCESS".equals(task.getStatus()) && !StringUtils.hasText(knowledgeBaseId)) {
            task.setStatus("FAILED");
            task.setStage("FAILED");
            task.setErrorCode("INVALID_RESPONSE");
            task.setErrorMessage("Agent Platform 入库成功响应缺少 knowledgeBaseId");
            agentTasks.updateById(task);
        }
        video.setAgentIngestStatus(task.getStatus());
        video.setAgentUpdatedAt(LocalDateTime.now());
        if ("SUCCESS".equals(task.getStatus())) {
            video.setAgentIngestVersion(task.getVersion());
            video.setAgentSourceKnowledgeBaseId(knowledgeBaseId);
            video.setAgentLastError(null);
        } else {
            video.setAgentIngestVersion(0);
            video.setAgentSourceKnowledgeBaseId(null);
            video.setAgentLastError(task.getErrorMessage());
        }
        videos.updateById(video);
    }

    private void reconcileBestEffort(VideoAgentTask task) {
        if (task.getAgentTaskId().startsWith("already-indexed:")) return;
        try {
            stateService.applySnapshot(task, client.task(task.getAgentTaskId(), task.getUserId(), null));
        } catch (AgentClientException failure) {
            if (!failure.isRetryable()) throw failure;
        }
    }

    private VideoFile requireTranscript(Long videoId, Long userId) {
        VideoFile video = videos.getVideoDetail(videoId, userId);
        int version = video.getTranscriptVersion() == null ? 0 : video.getTranscriptVersion();
        if (version < 1) throw new BizException(409, "视频尚未完成转录");
        return video;
    }

    private void requireEnabled() {
        if (!properties.isEnabled() || !properties.isIngestEnabled()) {
            throw new BizException(503, "Agent Platform 转录同步能力尚未启用");
        }
    }

    private VideoAgentTask latest(Long videoId, Long userId, int version) {
        List<VideoAgentTask> rows = agentTasks.selectList(new LambdaQueryWrapper<VideoAgentTask>()
                .eq(VideoAgentTask::getVideoId, videoId)
                .eq(VideoAgentTask::getUserId, userId)
                .eq(VideoAgentTask::getTaskType, "INGEST")
                .eq(VideoAgentTask::getVersion, version)
                .orderByDesc(VideoAgentTask::getCreatedAt)
                .last("LIMIT 1"));
        return rows.isEmpty() ? null : rows.get(0);
    }

    private AgentVideoSyncResponse response(VideoFile video, VideoAgentTask task) {
        boolean success = "SUCCESS".equalsIgnoreCase(task.getStatus())
                && Integer.valueOf(task.getVersion()).equals(video.getAgentIngestVersion());
        return AgentVideoSyncResponse.builder()
                .videoId(video.getId())
                .transcriptVersion(task.getVersion())
                .taskId(task.getAgentTaskId())
                .status(task.getStatus())
                .stage(task.getStage())
                .progress(task.getProgress())
                .knowledgeBaseId(success ? video.getAgentSourceKnowledgeBaseId() : null)
                .sourceKnowledgeBaseId(success ? video.getAgentSourceKnowledgeBaseId() : null)
                .errorCode(task.getErrorCode())
                .errorMessage(task.getErrorMessage())
                .build();
    }

    private AgentVideoSyncResponse unsynced(Long videoId, int version) {
        return AgentVideoSyncResponse.builder().videoId(videoId).transcriptVersion(version)
                .status("UNSYNCED").progress(0).build();
    }
}
