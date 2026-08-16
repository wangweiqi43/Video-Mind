package com.videomind.module.knowledge.service;

import com.videomind.module.knowledge.dto.DocumentUploadResponse;
import org.springframework.web.multipart.MultipartFile;

public interface KnowledgeDocumentApplicationService {
    DocumentUploadResponse uploadAndDispatch(Long userId, Long knowledgeBaseId, MultipartFile file,
                                             String idempotencyKey);
}
