package com.videomind.module.video.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.videomind.common.enums.UploadStatus;
import com.videomind.agentclient.AgentTaskClient;
import com.videomind.common.exception.BizException;
import com.videomind.config.RateLimitProperties;
import com.videomind.infrastructure.ratelimit.RateLimitService;
import com.videomind.infrastructure.storage.ObjectStorageService;
import com.videomind.infrastructure.storage.dto.StoredObject;
import com.videomind.module.agent.entity.VideoAgentTask;
import com.videomind.module.agent.mapper.VideoAgentTaskMapper;
import com.videomind.module.chat.entity.ChatMessage;
import com.videomind.module.chat.entity.ChatSession;
import com.videomind.module.chat.entity.ConversationSummary;
import com.videomind.module.chat.mapper.ChatMessageMapper;
import com.videomind.module.chat.mapper.ChatSessionMapper;
import com.videomind.module.chat.mapper.ConversationSummaryMapper;
import com.videomind.module.chat.service.ConversationContextService;
import com.videomind.module.knowledge.repository.KnowledgeStatusRepository;
import com.videomind.module.knowledge.repository.KnowledgeVectorRepository;
import com.videomind.module.task.entity.AiSummaryResult;
import com.videomind.module.task.entity.TaskRecord;
import com.videomind.module.task.entity.VideoTranscription;
import com.videomind.module.task.mapper.AiSummaryResultMapper;
import com.videomind.module.task.mapper.TaskRecordMapper;
import com.videomind.module.task.mapper.VideoTranscriptionMapper;
import com.videomind.module.video.dto.VideoUploadResponse;
import com.videomind.module.video.entity.VideoFile;
import com.videomind.module.video.entity.VideoUploadSession;
import com.videomind.module.video.mapper.VideoFileMapper;
import com.videomind.module.video.mapper.VideoUploadSessionMapper;
import com.videomind.module.video.service.VideoFileService;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.DigestUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

@Service
@RequiredArgsConstructor
public class VideoFileServiceImpl extends ServiceImpl<VideoFileMapper, VideoFile> implements VideoFileService {

    private static final DateTimeFormatter DATE_PATH_FORMATTER = DateTimeFormatter.ofPattern("yyyy/MM/dd");

    private final ObjectStorageService objectStorageService;
    private final RateLimitService rateLimitService;
    private final RateLimitProperties rateLimitProperties;
    private final TaskRecordMapper taskRecordMapper;
    private final VideoTranscriptionMapper videoTranscriptionMapper;
    private final AiSummaryResultMapper aiSummaryResultMapper;
    private final VideoUploadSessionMapper videoUploadSessionMapper;
    private final KnowledgeVectorRepository knowledgeVectorRepository;
    private final KnowledgeStatusRepository knowledgeStatusRepository;
    private final ChatSessionMapper chatSessionMapper;
    private final ChatMessageMapper chatMessageMapper;
    private final ConversationSummaryMapper conversationSummaryMapper;
    private final ConversationContextService conversationContextService;
    private final VideoAgentTaskMapper videoAgentTaskMapper;
    private final AgentTaskClient agentTaskClient;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public VideoUploadResponse upload(MultipartFile file, Long userId) {
        long totalStart = System.nanoTime();
        rateLimitService.acquire("upload:user:" + userId, rateLimitProperties.getUploadPermitsPerMinute());
        validateUploadFile(file);

        String originalFilename = normalizeFilename(file.getOriginalFilename());
        long md5Start = System.nanoTime();
        String fileMd5 = calculateMd5(file);
        long md5CostMs = elapsedMs(md5Start);
        VideoFile existing = getReusableUploadedByMd5(fileMd5, userId);
        if (existing != null) {
            throw new BizException(409, "文件已存在：%s（videoId=%d），无需重复上传。"
                    .formatted(existing.getOriginalFilename(), existing.getId()));
        }
        String objectKey = buildObjectKey(userId, originalFilename);

        StoredObject storedObject;
        long storageStart = System.nanoTime();
        try (InputStream inputStream = file.getInputStream()) {
            storedObject = objectStorageService.putObject(objectKey, inputStream, file.getSize(), file.getContentType());
        } catch (BizException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BizException(500, "读取上传文件失败：" + ex.getMessage());
        }
        long storageCostMs = elapsedMs(storageStart);

        LocalDateTime now = LocalDateTime.now();
        VideoFile videoFile = new VideoFile();
        videoFile.setUserId(userId);
        videoFile.setOriginalFilename(originalFilename);
        videoFile.setFileMd5(fileMd5);
        videoFile.setFileSize(file.getSize());
        videoFile.setContentType(file.getContentType());
        videoFile.setMinioBucket(storedObject.getBucket());
        videoFile.setMinioObjectKey(storedObject.getObjectKey());
        videoFile.setUploadStatus(UploadStatus.UPLOADED);
        videoFile.setCreatedTime(now);
        videoFile.setUpdatedTime(now);
        save(videoFile);

        VideoUploadResponse response = toUploadResponse(videoFile, "视频上传成功，已保存到 MinIO 并写入元数据。", false);
        response.setServerMd5CostMs(md5CostMs);
        response.setServerStorageCostMs(storageCostMs);
        response.setServerTotalCostMs(elapsedMs(totalStart));
        return response;
    }

    @Override
    public List<VideoFile> listVideos(Long userId) {
        return list(new LambdaQueryWrapper<VideoFile>()
                .eq(VideoFile::getUserId, userId)
                .orderByDesc(VideoFile::getCreatedTime));
    }

    @Override
    public VideoFile getUploadedByMd5(String fileMd5, Long userId) {
        if (!StringUtils.hasText(fileMd5)) {
            return null;
        }
        return getOne(new LambdaQueryWrapper<VideoFile>()
                .eq(VideoFile::getUserId, userId)
                .eq(VideoFile::getFileMd5, fileMd5)
                .eq(VideoFile::getUploadStatus, UploadStatus.UPLOADED)
                .orderByDesc(VideoFile::getCreatedTime)
                .last("LIMIT 1"));
    }

    @Override
    public VideoFile getReusableUploadedByMd5(String fileMd5, Long userId) {
        VideoFile uploaded = getUploadedByMd5(fileMd5, userId);
        if (uploaded == null) {
            return null;
        }
        if (objectStorageService.objectExists(uploaded.getMinioBucket(), uploaded.getMinioObjectKey())) {
            return uploaded;
        }
        return null;
    }

    @Override
    public VideoUploadResponse toUploadResponse(VideoFile videoFile, String message, boolean duplicate) {
        return VideoUploadResponse.builder()
                .videoId(videoFile.getId())
                .filename(videoFile.getOriginalFilename())
                .fileMd5(videoFile.getFileMd5())
                .fileSize(videoFile.getFileSize())
                .bucket(videoFile.getMinioBucket())
                .objectKey(videoFile.getMinioObjectKey())
                .implemented(true)
                .duplicate(duplicate)
                .message(message)
                .build();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteVideo(Long videoId, Long userId) {
        VideoFile videoFile = getVideoDetail(videoId, userId);
        boolean hasAgentTask=videoAgentTaskMapper.selectCount(Wrappers.<VideoAgentTask>lambdaQuery()
                .eq(VideoAgentTask::getVideoId,videoId).eq(VideoAgentTask::getUserId,userId))>0;
        if(hasAgentTask||StringUtils.hasText(videoFile.getAgentSourceKnowledgeBaseId())||StringUtils.hasText(videoFile.getAgentReportKnowledgeBaseId()))
            agentTaskClient.deleteVideoKnowledge(videoId,userId,null);
        videoAgentTaskMapper.delete(Wrappers.<VideoAgentTask>lambdaQuery()
                .eq(VideoAgentTask::getVideoId, videoId)
                .eq(VideoAgentTask::getUserId, userId));
        List<TaskRecord> tasks = taskRecordMapper.selectList(new LambdaQueryWrapper<TaskRecord>()
                .eq(TaskRecord::getUserId, userId)
                .eq(TaskRecord::getVideoId, videoId));
        List<Long> taskIds = tasks.stream().map(TaskRecord::getId).toList();

        for (Long taskId : taskIds) {
            knowledgeVectorRepository.deleteChunks(taskId);
            knowledgeStatusRepository.deleteStatus(taskId);
        }
        if (!taskIds.isEmpty()) {
            videoTranscriptionMapper.delete(Wrappers.<VideoTranscription>lambdaQuery()
                    .eq(VideoTranscription::getUserId, userId)
                    .in(VideoTranscription::getTaskId, taskIds));
            aiSummaryResultMapper.delete(Wrappers.<AiSummaryResult>lambdaQuery()
                    .eq(AiSummaryResult::getUserId, userId)
                    .in(AiSummaryResult::getTaskId, taskIds));
            taskRecordMapper.delete(Wrappers.<TaskRecord>lambdaQuery()
                    .eq(TaskRecord::getUserId, userId)
                    .in(TaskRecord::getId, taskIds));
        }
        videoUploadSessionMapper.delete(Wrappers.<VideoUploadSession>lambdaQuery()
                .eq(VideoUploadSession::getUserId, userId)
                .eq(VideoUploadSession::getVideoId, videoId));
        List<Long> sessionIds = chatSessionMapper.selectList(Wrappers.<ChatSession>lambdaQuery()
                        .eq(ChatSession::getUserId, userId)
                        .eq(ChatSession::getVideoId, videoId))
                .stream()
                .map(ChatSession::getId)
                .toList();
        if (!sessionIds.isEmpty()) {
            chatMessageMapper.delete(Wrappers.<ChatMessage>lambdaQuery()
                    .eq(ChatMessage::getUserId, userId)
                    .in(ChatMessage::getSessionId, sessionIds));
            conversationSummaryMapper.delete(Wrappers.<ConversationSummary>lambdaQuery()
                    .in(ConversationSummary::getConversationId, sessionIds));
            sessionIds.forEach(conversationContextService::evictContext);
            chatSessionMapper.delete(Wrappers.<ChatSession>lambdaQuery()
                    .eq(ChatSession::getUserId, userId)
                    .eq(ChatSession::getVideoId, videoId));
        }

        removeById(videoId);
        objectStorageService.removeObject(videoFile.getMinioBucket(), videoFile.getMinioObjectKey());
    }

    @Override
    public VideoFile getVideoDetail(Long videoId, Long userId) {
        VideoFile videoFile = getOne(new LambdaQueryWrapper<VideoFile>()
                .eq(VideoFile::getId, videoId)
                .eq(VideoFile::getUserId, userId));
        if (videoFile == null) {
            throw new BizException(404, "视频不存在或无权访问");
        }
        return videoFile;
    }

    private void validateUploadFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BizException(400, "上传文件不能为空");
        }

        String originalFilename = normalizeFilename(file.getOriginalFilename());
        if (!isSupportedVideoName(originalFilename)) {
            throw new BizException(400, "仅支持常见视频文件格式：mp4、mov、avi、mkv、webm、flv、wmv、m4v");
        }
    }

    private String calculateMd5(MultipartFile file) {
        try (InputStream inputStream = file.getInputStream()) {
            return DigestUtils.md5DigestAsHex(inputStream);
        } catch (Exception ex) {
            throw new BizException(500, "计算视频 MD5 失败：" + ex.getMessage());
        }
    }

    private String buildObjectKey(Long userId, String originalFilename) {
        String datePath = LocalDate.now().format(DATE_PATH_FORMATTER);
        String extension = getExtension(originalFilename);
        return "videos/%d/%s/%s%s".formatted(userId, datePath, UUID.randomUUID(), extension);
    }

    private String normalizeFilename(String originalFilename) {
        String cleaned = StringUtils.cleanPath(StringUtils.hasText(originalFilename) ? originalFilename : "video");
        String filename = StringUtils.getFilename(cleaned);
        return StringUtils.hasText(filename) ? filename : "video";
    }

    private boolean isSupportedVideoName(String filename) {
        String extension = getExtension(filename).toLowerCase(Locale.ROOT);
        return List.of(".mp4", ".mov", ".avi", ".mkv", ".webm", ".flv", ".wmv", ".m4v").contains(extension);
    }

    private String getExtension(String filename) {
        int index = filename.lastIndexOf('.');
        if (index < 0 || index == filename.length() - 1) {
            return "";
        }
        return filename.substring(index);
    }

    private long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
