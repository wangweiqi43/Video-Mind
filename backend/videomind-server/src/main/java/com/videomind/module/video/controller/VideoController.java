package com.videomind.module.video.controller;

import com.videomind.common.api.ApiResponse;
import com.videomind.common.context.MockUserContext;
import com.videomind.infrastructure.storage.ObjectStorageService;
import com.videomind.module.video.dto.ChunkUploadResponse;
import com.videomind.module.video.dto.MultipartUploadInitRequest;
import com.videomind.module.video.dto.MultipartUploadInitResponse;
import com.videomind.module.video.dto.MultipartUploadStatusResponse;
import com.videomind.module.video.dto.VideoDuplicateCheckResponse;
import com.videomind.module.video.dto.VideoTranscriptionResponse;
import com.videomind.module.video.dto.VideoUploadResponse;
import com.videomind.module.video.entity.VideoFile;
import com.videomind.module.video.service.MultipartUploadService;
import com.videomind.module.video.service.VideoFileService;
import com.videomind.module.video.service.VideoTranscriptionQueryService;
import jakarta.validation.Valid;
import java.io.InputStream;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.util.StringUtils;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/videos")
public class VideoController {

    private final VideoFileService videoFileService;
    private final VideoTranscriptionQueryService transcriptionQueryService;
    private final MultipartUploadService multipartUploadService;
    private final ObjectStorageService objectStorageService;

    @PostMapping("/upload")
    public ApiResponse<VideoUploadResponse> upload(@RequestPart("file") MultipartFile file) {
        return ApiResponse.success(videoFileService.upload(file, MockUserContext.currentUserId()));
    }

    @GetMapping("/check-md5")
    public ApiResponse<VideoDuplicateCheckResponse> checkMd5(@RequestParam String fileMd5) {
        Long userId = MockUserContext.currentUserId();
        VideoFile existing = videoFileService.getUploadedByMd5(fileMd5, userId);
        boolean exists = existing != null;
        return ApiResponse.success(VideoDuplicateCheckResponse.builder()
                .exists(exists)
                .videoId(exists ? existing.getId() : null)
                .filename(exists ? existing.getOriginalFilename() : null)
                .fileMd5(StringUtils.hasText(fileMd5) ? fileMd5 : null)
                .message(exists ? "文件已存在，无需重复上传。" : "文件未上传，可以继续。")
                .build());
    }

    @PostMapping("/multipart/init")
    public ApiResponse<MultipartUploadInitResponse> initMultipart(@Valid @RequestBody MultipartUploadInitRequest request) {
        return ApiResponse.success(multipartUploadService.init(request, MockUserContext.currentUserId()));
    }

    @PostMapping("/multipart/{uploadId}/chunk")
    public ApiResponse<ChunkUploadResponse> uploadChunk(
            @PathVariable String uploadId,
            @RequestParam Integer partNumber,
            @RequestParam String chunkMd5,
            @RequestPart("file") MultipartFile file) {
        return ApiResponse.success(multipartUploadService.uploadChunk(uploadId, partNumber, chunkMd5, file, MockUserContext.currentUserId()));
    }

    @PostMapping("/multipart/{uploadId}/complete")
    public ApiResponse<VideoUploadResponse> completeMultipart(@PathVariable String uploadId) {
        return ApiResponse.success(multipartUploadService.complete(uploadId, MockUserContext.currentUserId()));
    }

    @GetMapping("/multipart/{uploadId}/status")
    public ApiResponse<MultipartUploadStatusResponse> multipartStatus(@PathVariable String uploadId) {
        return ApiResponse.success(multipartUploadService.status(uploadId, MockUserContext.currentUserId()));
    }

    @GetMapping("/list")
    public ApiResponse<List<VideoFile>> list() {
        return ApiResponse.success(videoFileService.listVideos(MockUserContext.currentUserId()));
    }

    @GetMapping("/{videoId}")
    public ApiResponse<VideoFile> detail(@PathVariable Long videoId) {
        return ApiResponse.success(videoFileService.getVideoDetail(videoId, MockUserContext.currentUserId()));
    }

    @GetMapping("/{videoId}/transcription")
    public ApiResponse<VideoTranscriptionResponse> transcription(@PathVariable Long videoId) {
        return ApiResponse.success(transcriptionQueryService.latest(videoId, MockUserContext.currentUserId()));
    }

    @GetMapping("/{videoId}/stream")
    public ResponseEntity<InputStreamResource> stream(@PathVariable Long videoId) {
        VideoFile videoFile = videoFileService.getVideoDetail(videoId, MockUserContext.currentUserId());
        InputStream inputStream = objectStorageService.getObject(videoFile.getMinioBucket(), videoFile.getMinioObjectKey());
        MediaType mediaType = StringUtils.hasText(videoFile.getContentType())
                ? MediaType.parseMediaType(videoFile.getContentType())
                : MediaType.APPLICATION_OCTET_STREAM;
        return ResponseEntity.ok()
                .contentType(mediaType)
                .contentLength(videoFile.getFileSize())
                .body(new InputStreamResource(inputStream));
    }

    @DeleteMapping("/{videoId}")
    public ApiResponse<Void> delete(@PathVariable Long videoId) {
        videoFileService.deleteVideo(videoId, MockUserContext.currentUserId());
        return ApiResponse.success(null);
    }
}
