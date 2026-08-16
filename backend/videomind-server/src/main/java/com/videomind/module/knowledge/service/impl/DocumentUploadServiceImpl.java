package com.videomind.module.knowledge.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.videomind.common.enums.KnowledgeBaseType;
import com.videomind.common.enums.KnowledgeLifecycleStatus;
import com.videomind.common.exception.BizException;
import com.videomind.infrastructure.storage.ObjectStorageService;
import com.videomind.infrastructure.storage.dto.StoredObject;
import com.videomind.module.knowledge.dto.DocumentUploadResponse;
import com.videomind.module.knowledge.entity.DocumentVersion;
import com.videomind.module.knowledge.entity.DocumentUploadIdempotency;
import com.videomind.module.knowledge.entity.KnowledgeBase;
import com.videomind.module.knowledge.entity.KnowledgeDocument;
import com.videomind.module.knowledge.ingest.DocumentFileValidator;
import com.videomind.module.knowledge.mapper.DocumentVersionMapper;
import com.videomind.module.knowledge.mapper.DocumentUploadIdempotencyMapper;
import com.videomind.module.knowledge.mapper.KnowledgeBaseMapper;
import com.videomind.module.knowledge.mapper.KnowledgeDocumentMapper;
import com.videomind.module.knowledge.service.DocumentUploadService;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
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
    private final DocumentUploadIdempotencyMapper idempotencyMapper;
    private final ObjectStorageService storage;
    private final DocumentFileValidator validator;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public DocumentUploadResponse upload(Long userId, Long knowledgeBaseId, MultipartFile file,
                                         String idempotencyKey) {
        KnowledgeBase knowledgeBase = requireWritableUserBase(userId, knowledgeBaseId);
        String filename = normalizeFilename(file.getOriginalFilename());
        validateIdempotencyKey(idempotencyKey);
        Path staged = stage(file);
        try {
        long fileSize = Files.size(staged);
        String contentType = validator.validateAndContentType(filename, staged);
        String sha256 = sha256(staged);
        String requestFingerprint = sha256(userId + "|" + knowledgeBaseId + "|"
                + filename + "|" + fileSize + "|" + sha256);
        DocumentUploadIdempotency previous = findIdempotency(userId, knowledgeBaseId, idempotencyKey);
        if (previous != null) {
            if (!requestFingerprint.equals(previous.getRequestFingerprint())) {
                throw new BizException(409, "IDEMPOTENCY_CONFLICT");
            }
            KnowledgeDocument priorDocument = documentMapper.selectById(previous.getDocumentId());
            DocumentVersion priorVersion = versionMapper.selectById(previous.getDocumentVersionId());
            return response(priorDocument, priorVersion, true, previous.getProcessingTaskId());
        }
        KnowledgeDocument duplicate = findDuplicate(userId, knowledgeBaseId, sha256);
        if (duplicate != null) {
            DocumentVersion version = latestVersion(duplicate.getId());
            saveIdempotency(userId, knowledgeBaseId, idempotencyKey, requestFingerprint,
                    duplicate.getId(), version.getId());
            return response(duplicate, version, true, null);
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
        StoredObject stored;
        try (InputStream input = Files.newInputStream(staged)) {
            stored = storage.putObject(objectKey, input, fileSize, contentType);
        }
        removeStoredObjectAfterRollback(stored);

        DocumentVersion version = new DocumentVersion();
        version.setDocumentId(document.getId());
        version.setVersionNumber(1);
        version.setOriginalBucket(stored.getBucket());
        version.setOriginalObjectKey(stored.getObjectKey());
        version.setOriginalFileSize(fileSize);
        version.setOriginalContentType(contentType);
        version.setProcessingStage(validator.mineruRequired(filename) ? "MINERU_QUEUED" : "READ_PARSE");
        version.setVisualStatus("NOT_APPLICABLE");
        version.setImageCount(0);
        version.setDescribedImageCount(0);
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
        saveIdempotency(userId, knowledgeBaseId, idempotencyKey, requestFingerprint,
                document.getId(), version.getId());
        return response(document, version, false, null);
        } catch (BizException known) {
            throw known;
        } catch (Exception failure) {
            throw new BizException(500, "登记知识库文件失败：" + failure.getMessage());
        } finally {
            try { Files.deleteIfExists(staged); } catch (Exception ignored) { }
        }
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

    private static Path stage(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BizException(400, "不能上传空文件");
        }
        if (file.getSize() > MAX_FILE_BYTES) {
            throw new BizException(413, "文件不能超过 50MB");
        }
        try {
            Path directory = Path.of("runtime", "document-upload");
            Files.createDirectories(directory);
            Path target = Files.createTempFile(directory, "upload-", ".part");
            long copied = 0;
            byte[] buffer = new byte[64 * 1024];
            try (InputStream input = file.getInputStream();
                 OutputStream output = Files.newOutputStream(target, StandardOpenOption.TRUNCATE_EXISTING)) {
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    copied += read;
                    if (copied > MAX_FILE_BYTES) {
                        throw new BizException(413, "文件不能超过 50MB");
                    }
                    output.write(buffer, 0, read);
                }
            }
            if (copied == 0) {
                throw new BizException(400, "不能上传空文件");
            }
            return target;
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
                + "/v1/original/" + safe;
    }

    private static String sha256(Path path) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(path)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) >= 0) digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private void validateIdempotencyKey(String value) {
        if (!StringUtils.hasText(value) || value.length() > 200) {
            throw new BizException(400, "Idempotency-Key 必须是有效 UUID");
        }
        try { UUID.fromString(value); } catch (Exception invalid) {
            throw new BizException(400, "Idempotency-Key 必须是有效 UUID");
        }
    }

    private DocumentUploadIdempotency findIdempotency(Long userId, Long knowledgeBaseId, String key) {
        return idempotencyMapper.selectOne(Wrappers.<DocumentUploadIdempotency>lambdaQuery()
                .eq(DocumentUploadIdempotency::getUserId, userId)
                .eq(DocumentUploadIdempotency::getKnowledgeBaseId, knowledgeBaseId)
                .eq(DocumentUploadIdempotency::getIdempotencyKey, key).last("LIMIT 1"));
    }

    private void saveIdempotency(Long userId, Long knowledgeBaseId, String key, String fingerprint,
                                 Long documentId, Long versionId) {
        DocumentUploadIdempotency value = new DocumentUploadIdempotency();
        LocalDateTime now = LocalDateTime.now();
        value.setUserId(userId);
        value.setKnowledgeBaseId(knowledgeBaseId);
        value.setIdempotencyKey(key);
        value.setRequestFingerprint(fingerprint);
        value.setDocumentId(documentId);
        value.setDocumentVersionId(versionId);
        value.setCreatedTime(now);
        value.setUpdatedTime(now);
        idempotencyMapper.insert(value);
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
                                                   boolean duplicated, Long processingTaskId) {
        return new DocumentUploadResponse(document.getId(), version == null ? null : version.getId(),
                document.getTitle(), document.getSha256(), document.getStatus(),
                version == null ? null : version.getProcessingStage(), duplicated, null,
                processingTaskId, processingTaskId != null);
    }
}
