package com.videomind.module.video.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.videomind.common.enums.UploadSessionStatus;
import com.videomind.common.enums.UploadStatus;
import com.videomind.common.exception.BizException;
import com.videomind.config.RateLimitProperties;
import com.videomind.config.UploadProperties;
import com.videomind.infrastructure.ratelimit.RateLimitService;
import com.videomind.infrastructure.storage.ObjectStorageService;
import com.videomind.infrastructure.storage.dto.StoredObject;
import com.videomind.module.video.dto.ChunkUploadResponse;
import com.videomind.module.video.dto.MultipartUploadInitRequest;
import com.videomind.module.video.dto.MultipartUploadInitResponse;
import com.videomind.module.video.dto.MultipartUploadStatusResponse;
import com.videomind.module.video.dto.VideoUploadResponse;
import com.videomind.module.video.entity.VideoFile;
import com.videomind.module.video.entity.VideoUploadSession;
import com.videomind.module.video.mapper.VideoUploadSessionMapper;
import com.videomind.module.video.service.MultipartUploadService;
import com.videomind.module.video.service.VideoFileService;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.DigestUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

@Service
@RequiredArgsConstructor
public class MultipartUploadServiceImpl extends ServiceImpl<VideoUploadSessionMapper, VideoUploadSession>
        implements MultipartUploadService {

    private final UploadProperties uploadProperties;
    private final ObjectStorageService objectStorageService;
    private final VideoFileService videoFileService;
    private final StringRedisTemplate stringRedisTemplate;
    private final RedissonClient redissonClient;
    private final RateLimitService rateLimitService;
    private final RateLimitProperties rateLimitProperties;

    @Override
    public MultipartUploadInitResponse init(MultipartUploadInitRequest request, Long userId) {
        rateLimitService.acquire("upload:init:user:" + userId, rateLimitProperties.getUploadPermitsPerMinute());
        validateFilename(request.getFilename());
        VideoFile uploaded = videoFileService.getReusableUploadedByMd5(request.getFileMd5(), userId);
        if (uploaded != null) {
            return MultipartUploadInitResponse.builder()
                    .status("UPLOADED")
                    .uploadedParts(List.of())
                    .video(videoFileService.toUploadResponse(uploaded, "文件已存在，已秒传成功。", true))
                    .build();
        }
        VideoUploadSession existing = getOne(new LambdaQueryWrapper<VideoUploadSession>()
                .eq(VideoUploadSession::getUserId, userId)
                .eq(VideoUploadSession::getFileMd5, request.getFileMd5())
                .eq(VideoUploadSession::getUploadStatus, UploadSessionStatus.UPLOADING)
                .last("LIMIT 1"));
        if (existing != null) {
            return MultipartUploadInitResponse.builder()
                    .uploadId(existing.getUploadId())
                    .uploadedParts(readUploadedParts(existing))
                    .status(existing.getUploadStatus().name())
                    .build();
        }

        LocalDateTime now = LocalDateTime.now();
        VideoUploadSession session = new VideoUploadSession();
        session.setUploadId(UUID.randomUUID().toString().replace("-", ""));
        session.setUserId(userId);
        session.setOriginalFilename(normalizeFilename(request.getFilename()));
        session.setFileMd5(request.getFileMd5());
        session.setFileSize(request.getFileSize());
        session.setContentType(request.getContentType());
        session.setTotalParts(request.getTotalParts());
        session.setChunkSize(request.getChunkSize());
        session.setUploadedParts(0);
        session.setUploadStatus(UploadSessionStatus.UPLOADING);
        session.setCreatedTime(now);
        session.setUpdatedTime(now);
        save(session);

        return MultipartUploadInitResponse.builder()
                .uploadId(session.getUploadId())
                .uploadedParts(List.of())
                .status(session.getUploadStatus().name())
                .build();
    }

    @Override
    public ChunkUploadResponse uploadChunk(String uploadId, Integer partNumber, String chunkMd5, MultipartFile file, Long userId) {
        rateLimitService.acquire("upload:chunk:user:" + userId, rateLimitProperties.getUploadPermitsPerMinute());
        VideoUploadSession session = getSession(uploadId, userId);
        if (session.getUploadStatus() != UploadSessionStatus.UPLOADING) {
            throw new BizException(400, "上传会话状态不允许继续上传分片");
        }
        if (partNumber == null || partNumber < 1 || partNumber > session.getTotalParts()) {
            throw new BizException(400, "分片序号非法");
        }
        if (file == null || file.isEmpty()) {
            throw new BizException(400, "分片文件不能为空");
        }
        if (!isMd5(chunkMd5)) {
            throw new BizException(400, "分片 MD5 非法");
        }

        RLock lock = redissonClient.getLock("lock:upload:chunk:" + uploadId + ":" + partNumber);
        lock.lock(60, TimeUnit.SECONDS);
        try {
            Path partPath = partPath(uploadId, partNumber);
            Files.createDirectories(partPath.getParent());
            String bitmapKey = bitmapKey(uploadId);
            if (isPartAlreadyUploaded(bitmapKey, partNumber, partPath)) {
                int uploadedCount = readUploadedParts(session).size();
                return ChunkUploadResponse.builder()
                        .uploadId(uploadId)
                        .partNumber(partNumber)
                        .uploaded(true)
                        .skipped(true)
                        .uploadedPartsCount(uploadedCount)
                        .chunkMd5(chunkMd5.toLowerCase(Locale.ROOT))
                        .build();
            }
            Path tempPath = partPath.resolveSibling(partPath.getFileName() + ".tmp");
            try {
                file.transferTo(tempPath);
                String actualChunkMd5;
                try (InputStream inputStream = Files.newInputStream(tempPath)) {
                    actualChunkMd5 = DigestUtils.md5DigestAsHex(inputStream);
                }
                if (!chunkMd5.equalsIgnoreCase(actualChunkMd5)) {
                    Files.deleteIfExists(tempPath);
                    throw new BizException(400, "分片 MD5 校验失败，请重新上传该分片");
                }
                try {
                    Files.move(tempPath, partPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (IOException ex) {
                    Files.move(tempPath, partPath, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (BizException ex) {
                throw ex;
            } catch (IOException ex) {
                Files.deleteIfExists(tempPath);
                throw ex;
            }
            stringRedisTemplate.opsForValue().setBit(bitmapKey, partNumber - 1, true);
            stringRedisTemplate.expire(bitmapKey, Duration.ofSeconds(uploadProperties.getBitmapTtlSeconds()));
            int uploadedCount = readUploadedParts(session).size();
            session.setUploadedParts(uploadedCount);
            session.setUpdatedTime(LocalDateTime.now());
            updateById(session);
            return ChunkUploadResponse.builder()
                    .uploadId(uploadId)
                    .partNumber(partNumber)
                    .uploaded(true)
                    .skipped(false)
                    .uploadedPartsCount(uploadedCount)
                    .chunkMd5(chunkMd5.toLowerCase(Locale.ROOT))
                    .build();
        } catch (BizException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BizException(500, "上传分片失败：" + ex.getMessage());
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    @Override
    public MultipartUploadStatusResponse status(String uploadId, Long userId) {
        VideoUploadSession session = getSession(uploadId, userId);
        List<Integer> uploadedParts = readUploadedParts(session);
        return MultipartUploadStatusResponse.builder()
                .uploadId(uploadId)
                .totalParts(session.getTotalParts())
                .uploadedPartsCount(uploadedParts.size())
                .uploadedParts(uploadedParts)
                .status(session.getUploadStatus().name())
                .videoId(session.getVideoId())
                .build();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public VideoUploadResponse complete(String uploadId, Long userId) {
        long totalStart = System.nanoTime();
        rateLimitService.acquire("upload:complete:user:" + userId, rateLimitProperties.getUploadPermitsPerMinute());
        RLock lock = redissonClient.getLock("lock:upload:complete:" + uploadId);
        lock.lock(10, TimeUnit.MINUTES);
        try {
            VideoUploadSession session = getSession(uploadId, userId);
            if (session.getUploadStatus() == UploadSessionStatus.COMPLETED) {
                stringRedisTemplate.delete(bitmapKey(uploadId));
                cleanupUploadDir(uploadId);
                return responseForCompleted(session);
            }
            ensureAllPartsUploaded(session);
            long mergeStart = System.nanoTime();
            Path mergedFile = mergeParts(session);
            long mergeCostMs = elapsedMs(mergeStart);
            String actualMd5;
            long md5Start = System.nanoTime();
            try (InputStream inputStream = Files.newInputStream(mergedFile)) {
                actualMd5 = DigestUtils.md5DigestAsHex(inputStream);
            }
            long md5CostMs = elapsedMs(md5Start);
            if (!session.getFileMd5().equalsIgnoreCase(actualMd5)) {
                session.setUploadStatus(UploadSessionStatus.FAILED);
                session.setUpdatedTime(LocalDateTime.now());
                updateById(session);
                throw new BizException(400, "文件 MD5 校验失败，请重新上传");
            }
            VideoFile uploaded = videoFileService.getReusableUploadedByMd5(actualMd5, userId);
            if (uploaded != null) {
                session.setVideoId(uploaded.getId());
                session.setUploadStatus(UploadSessionStatus.COMPLETED);
                session.setUploadedParts(session.getTotalParts());
                session.setUpdatedTime(LocalDateTime.now());
                updateById(session);
                stringRedisTemplate.delete(bitmapKey(uploadId));
                cleanupUploadDir(uploadId);
                VideoUploadResponse response = videoFileService.toUploadResponse(uploaded, "文件已存在，已秒传成功。", true);
                response.setServerMergeCostMs(mergeCostMs);
                response.setServerMd5CostMs(md5CostMs);
                response.setServerTotalCostMs(elapsedMs(totalStart));
                return response;
            }

            StoredObject storedObject;
            long storageStart = System.nanoTime();
            try (InputStream inputStream = Files.newInputStream(mergedFile)) {
                storedObject = objectStorageService.putObject(buildObjectKey(userId, session.getOriginalFilename()),
                        inputStream, Files.size(mergedFile), session.getContentType());
            }
            long storageCostMs = elapsedMs(storageStart);
            VideoFile videoFile = saveVideoFile(session, storedObject);
            session.setVideoId(videoFile.getId());
            session.setUploadStatus(UploadSessionStatus.COMPLETED);
            session.setUploadedParts(session.getTotalParts());
            session.setUpdatedTime(LocalDateTime.now());
            updateById(session);
            stringRedisTemplate.delete(bitmapKey(uploadId));
            cleanupUploadDir(uploadId);

            VideoUploadResponse response = videoFileService.toUploadResponse(videoFile, "分片上传完成，已合并并写入 MinIO。", false);
            response.setServerMergeCostMs(mergeCostMs);
            response.setServerMd5CostMs(md5CostMs);
            response.setServerStorageCostMs(storageCostMs);
            response.setServerTotalCostMs(elapsedMs(totalStart));
            return response;
        } catch (BizException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BizException(500, "完成分片上传失败：" + ex.getMessage());
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private VideoUploadSession getSession(String uploadId, Long userId) {
        VideoUploadSession session = getOne(new LambdaQueryWrapper<VideoUploadSession>()
                .eq(VideoUploadSession::getUploadId, uploadId)
                .eq(VideoUploadSession::getUserId, userId));
        if (session == null) {
            throw new BizException(404, "上传会话不存在或无权访问");
        }
        return session;
    }

    private List<Integer> readUploadedParts(VideoUploadSession session) {
        List<Integer> uploaded = new ArrayList<>();
        for (int i = 0; i < session.getTotalParts(); i++) {
            Boolean bit = stringRedisTemplate.opsForValue().getBit(bitmapKey(session.getUploadId()), i);
            if (Boolean.TRUE.equals(bit)) {
                uploaded.add(i + 1);
            }
        }
        return uploaded;
    }

    private void ensureAllPartsUploaded(VideoUploadSession session) {
        List<Integer> uploadedParts = readUploadedParts(session);
        if (uploadedParts.size() != session.getTotalParts()) {
            throw new BizException(400, "分片未上传完成，当前已上传 " + uploadedParts.size() + "/" + session.getTotalParts());
        }
    }

    private Path mergeParts(VideoUploadSession session) throws Exception {
        Path mergedFile = uploadDir(session.getUploadId()).resolve("merged-" + session.getOriginalFilename());
        try (OutputStream outputStream = Files.newOutputStream(mergedFile)) {
            for (int partNumber = 1; partNumber <= session.getTotalParts(); partNumber++) {
                Files.copy(partPath(session.getUploadId(), partNumber), outputStream);
            }
        }
        return mergedFile;
    }

    private VideoFile saveVideoFile(VideoUploadSession session, StoredObject storedObject) {
        LocalDateTime now = LocalDateTime.now();
        VideoFile videoFile = new VideoFile();
        videoFile.setUserId(session.getUserId());
        videoFile.setOriginalFilename(session.getOriginalFilename());
        videoFile.setFileMd5(session.getFileMd5());
        videoFile.setFileSize(session.getFileSize());
        videoFile.setContentType(session.getContentType());
        videoFile.setMinioBucket(storedObject.getBucket());
        videoFile.setMinioObjectKey(storedObject.getObjectKey());
        videoFile.setUploadStatus(UploadStatus.UPLOADED);
        videoFile.setCreatedTime(now);
        videoFile.setUpdatedTime(now);
        videoFileService.save(videoFile);
        return videoFile;
    }

    private VideoUploadResponse responseForCompleted(VideoUploadSession session) {
        VideoFile videoFile = videoFileService.getVideoDetail(session.getVideoId(), session.getUserId());
        return videoFileService.toUploadResponse(videoFile, "该分片上传已完成。", false);
    }

    private void cleanupUploadDir(String uploadId) {
        Path dir = uploadDir(uploadId);
        if (!Files.exists(dir)) {
            return;
        }
        try (var paths = Files.walk(dir)) {
            paths.sorted((left, right) -> right.getNameCount() - left.getNameCount())
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException ignored) {
                            // Temporary upload files can be cleaned on the next run if deletion is blocked.
                        }
                    });
        } catch (IOException ignored) {
            // Ignore cleanup failures; upload completion should not fail because temp deletion failed.
        }
    }

    private Path uploadDir(String uploadId) {
        return Path.of(uploadProperties.getChunkWorkDir(), uploadId).toAbsolutePath();
    }

    private Path partPath(String uploadId, Integer partNumber) {
        return uploadDir(uploadId).resolve("part-" + partNumber);
    }

    private boolean isPartAlreadyUploaded(String bitmapKey, Integer partNumber, Path partPath) {
        Boolean uploaded = stringRedisTemplate.opsForValue().getBit(bitmapKey, partNumber - 1);
        return Boolean.TRUE.equals(uploaded) && Files.exists(partPath);
    }

    private String bitmapKey(String uploadId) {
        return uploadProperties.getBitmapPrefix() + uploadId;
    }

    private String buildObjectKey(Long userId, String originalFilename) {
        String extension = getExtension(originalFilename);
        return "videos/%d/multipart/%s%s".formatted(userId, UUID.randomUUID(), extension);
    }

    private long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private void validateFilename(String filename) {
        if (!isSupportedVideoName(normalizeFilename(filename))) {
            throw new BizException(400, "仅支持常见视频文件格式：mp4、mov、avi、mkv、webm、flv、wmv、m4v");
        }
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

    private boolean isMd5(String value) {
        return StringUtils.hasText(value) && value.matches("(?i)^[0-9a-f]{32}$");
    }

    private String getExtension(String filename) {
        int index = filename.lastIndexOf('.');
        if (index < 0 || index == filename.length() - 1) {
            return "";
        }
        return filename.substring(index);
    }
}
