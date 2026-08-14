package com.videomind.module.knowledge.service;

import com.videomind.module.knowledge.dto.DocumentUploadResponse;
import org.springframework.web.multipart.MultipartFile;

public interface DocumentUploadService {
    DocumentUploadResponse upload(Long userId, Long knowledgeBaseId, MultipartFile file);
}
