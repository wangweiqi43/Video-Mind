package com.videomind.module.knowledge.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.videomind.common.enums.KnowledgeBaseType;
import com.videomind.common.enums.KnowledgeLifecycleStatus;
import com.videomind.common.exception.BizException;
import com.videomind.infrastructure.storage.ObjectStorageService;
import com.videomind.infrastructure.storage.dto.StoredObject;
import com.videomind.module.knowledge.dto.DocumentUploadResponse;
import com.videomind.module.knowledge.entity.DocumentVersion;
import com.videomind.module.knowledge.entity.KnowledgeBase;
import com.videomind.module.knowledge.entity.KnowledgeDocument;
import com.videomind.module.knowledge.ingest.DocumentFileValidator;
import com.videomind.module.knowledge.mapper.DocumentVersionMapper;
import com.videomind.module.knowledge.mapper.KnowledgeBaseMapper;
import com.videomind.module.knowledge.mapper.KnowledgeDocumentMapper;
import com.videomind.module.knowledge.service.DocumentUploadService;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

@Service
@RequiredArgsConstructor
public class DocumentUploadServiceImpl implements DocumentUploadService {
    static final int MAX_FILE_BYTES = 50 * 1024 * 1024;

    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final KnowledgeDocumentMapper documentMapper;
    private final DocumentVersionMapper versionMapper;
    private final ObjectStorageService storage;
    private final DocumentFileValidator validator;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public DocumentUploadResponse upload(Long userId, Long knowledgeBaseId, MultipartFile file) {
        KnowledgeBase knowledgeBase = requireWritableUserBase(userId, knowledgeBaseId);
        byte[] bytes = read(file);
        String filename = normalizeFilename(file.getOriginalFilename());
        String contentType = validator.validateAndContentType(filename, bytes);
        String sha256 = sha256(bytes);
        KnowledgeDocument duplicate = findDuplicate(userId, knowledgeBaseId, sha256);
        if (duplicate != null) {
            DocumentVersion version = latestVersion(duplicate.getId());
            return response(duplicate, version, true);
        }

        LocalDateTime now = LocalDateTime.now();
        KnowledgeDocument document = new KnowledgeDocument();
        document.setKnowledgeBaseId(knowledgeBaseId);
        document.setUserId(userId);
        document.setSourceType("UPLOAD");
        document.setTitle(filename);
        document.setSha256(sha256);
        document.setDedupeKey(sha256);
        document.setStatus(KnowledgeLifecycleStatus.UPLOADING);
        document.setActive(true);
        document.setCreatedTime(now);
        document.setUpdatedTime(now);
        document.setDeleted(0);
        documentMapper.insert(document);

        String objectKey = objectKey(userId, knowledgeBaseId, document.getId(), filename);
        StoredObject stored = storage.putObject(objectKey, new ByteArrayInputStream(bytes), bytes.length, contentType);
        removeStoredObjectAfterRollback(stored);

        DocumentVersion version = new DocumentVersion();
        version.setDocumentId(document.getId());
        version.setVersionNumber(1);
        version.setOriginalBucket(stored.getBucket());
        version.setOriginalObjectKey(stored.getObjectKey());
        version.setOriginalFileSize((long) bytes.length);
        version.setOriginalContentType(contentType);
        version.setProcessingStage(validator.mineruRequired(filename) ? "MINERU_QUEUED" : "READ_PARSE");
        version.setIndexStatus("PENDING");
        version.setChunkCount(0);
        version.setCreatedTime(now);
        version.setUpdatedTime(now);
        versionMapper.insert(version);

        document.setStatus(KnowledgeLifecycleStatus.PROCESSING);
        document.setUpdatedTime(now);
        documentMapper.updateById(document);
        if (knowledgeBase.getStatus() != KnowledgeLifecycleStatus.PROCESSING) {
            knowledgeBase.setStatus(KnowledgeLifecycleStatus.PROCESSING);
            knowledgeBase.setUpdatedTime(now);
            knowledgeBaseMapper.updateById(knowledgeBase);
        }
        return response(document, version, false);
    }

    private KnowledgeBase requireWritableUserBase(Long userId, Long knowledgeBaseId) {
        KnowledgeBase value = knowledgeBaseMapper.selectOne(Wrappers.<KnowledgeBase>lambdaQuery()
                .eq(KnowledgeBase::getId, knowledgeBaseId)
                .eq(KnowledgeBase::getUserId, userId)
                .eq(KnowledgeBase::getActive, true)
                .last("LIMIT 1"));
        if (value == null) {
            throw new BizException(404, "知识库不存在或无权访问");
        }
        if (value.getType() != KnowledgeBaseType.USER) {
            throw new BizException(409, "视频系统知识库只能由视频处理链路写入");
        }
        if (value.getStatus() == KnowledgeLifecycleStatus.DELETING) {
            throw new BizException(409, "知识库正在删除，不能上传文档");
        }
        return value;
    }

    private KnowledgeDocument findDuplicate(Long userId, Long knowledgeBaseId, String sha256) {
        return documentMapper.selectOne(Wrappers.<KnowledgeDocument>lambdaQuery()
                .eq(KnowledgeDocument::getUserId, userId)
                .eq(KnowledgeDocument::getKnowledgeBaseId, knowledgeBaseId)
                .eq(KnowledgeDocument::getDedupeKey, sha256)
                .eq(KnowledgeDocument::getActive, true)
                .last("LIMIT 1"));
    }

    private DocumentVersion latestVersion(Long documentId) {
        return versionMapper.selectOne(Wrappers.<DocumentVersion>lambdaQuery()
                .eq(DocumentVersion::getDocumentId, documentId)
                .orderByDesc(DocumentVersion::getVersionNumber)
                .last("LIMIT 1"));
    }

    private static byte[] read(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BizException(400, "不能上传空文件");
        }
        if (file.getSize() > MAX_FILE_BYTES) {
            throw new BizException(413, "文件不能超过 50MB");
        }
        try {
            byte[] bytes = file.getBytes();
            if (bytes.length == 0) {
                throw new BizException(400, "不能上传空文件");
            }
            return bytes;
        } catch (BizException known) {
            throw known;
        } catch (Exception failure) {
            throw new BizException(400, "无法读取上传文件");
        }
    }

    private static String normalizeFilename(String original) {
        String value = StringUtils.getFilename(original);
        if (!StringUtils.hasText(value)) {
            throw new BizException(400, "文件名不能为空");
        }
        value = value.trim();
        return value.length() > 255 ? value.substring(value.length() - 255) : value;
    }

    private static String objectKey(Long userId, Long knowledgeBaseId, Long documentId, String filename) {
        String safe = filename.replaceAll("[^a-zA-Z0-9._-]", "_");
        if (!StringUtils.hasText(safe)) {
            safe = "document";
        }
        return "knowledge/" + userId + "/" + knowledgeBaseId + "/" + documentId
                + "/v1/original/" + UUID.randomUUID() + "-" + safe;
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private void removeStoredObjectAfterRollback(StoredObject stored) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_ROLLED_BACK) {
                    storage.removeObject(stored.getBucket(), stored.getObjectKey());
                }
            }
        });
    }

    private static DocumentUploadResponse response(KnowledgeDocument document, DocumentVersion version,
                                                   boolean duplicated) {
        return new DocumentUploadResponse(document.getId(), version == null ? null : version.getId(),
                document.getTitle(), document.getSha256(), document.getStatus(),
                version == null ? null : version.getProcessingStage(), duplicated);
    }
}
