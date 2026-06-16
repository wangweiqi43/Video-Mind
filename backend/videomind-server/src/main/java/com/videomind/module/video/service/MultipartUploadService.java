package com.videomind.module.video.service;

import com.videomind.module.video.dto.ChunkUploadResponse;
import com.videomind.module.video.dto.MultipartUploadInitRequest;
import com.videomind.module.video.dto.MultipartUploadInitResponse;
import com.videomind.module.video.dto.MultipartUploadStatusResponse;
import com.videomind.module.video.dto.VideoUploadResponse;
import org.springframework.web.multipart.MultipartFile;

public interface MultipartUploadService {

    MultipartUploadInitResponse init(MultipartUploadInitRequest request, Long userId);

    ChunkUploadResponse uploadChunk(String uploadId, Integer partNumber, MultipartFile file, Long userId);

    MultipartUploadStatusResponse status(String uploadId, Long userId);

    VideoUploadResponse complete(String uploadId, Long userId);
}

