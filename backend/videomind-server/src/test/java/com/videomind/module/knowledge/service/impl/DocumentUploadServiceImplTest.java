package com.videomind.module.knowledge.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.videomind.common.enums.KnowledgeBaseType;
import com.videomind.common.enums.KnowledgeLifecycleStatus;
import com.videomind.common.exception.BizException;
import com.videomind.infrastructure.storage.ObjectStorageService;
import com.videomind.infrastructure.storage.dto.StoredObject;
import com.videomind.module.knowledge.entity.DocumentVersion;
import com.videomind.module.knowledge.entity.KnowledgeBase;
import com.videomind.module.knowledge.entity.KnowledgeDocument;
import com.videomind.module.knowledge.ingest.DocumentFileValidator;
import com.videomind.module.knowledge.mapper.DocumentVersionMapper;
import com.videomind.module.knowledge.mapper.KnowledgeBaseMapper;
import com.videomind.module.knowledge.mapper.KnowledgeDocumentMapper;
import com.videomind.module.knowledge.mapper.DocumentUploadIdempotencyMapper;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import com.videomind.module.knowledge.entity.DocumentUploadIdempotency;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

class DocumentUploadServiceImplTest {
    private final KnowledgeBaseMapper bases = mock(KnowledgeBaseMapper.class);
    private final KnowledgeDocumentMapper documents = mock(KnowledgeDocumentMapper.class);
    private final DocumentVersionMapper versions = mock(DocumentVersionMapper.class);
    private final DocumentUploadIdempotencyMapper idempotency = mock(DocumentUploadIdempotencyMapper.class);
    private final ObjectStorageService storage = mock(ObjectStorageService.class);
    private final DocumentUploadServiceImpl service = new DocumentUploadServiceImpl(
            bases, documents, versions, idempotency, storage, new DocumentFileValidator());
    private static final String KEY = "11111111-1111-1111-1111-111111111111";
    private final KnowledgeBase userBase = new KnowledgeBase();

    @BeforeEach
    void setUp() {
        userBase.setId(11L);
        userBase.setUserId(7L);
        userBase.setType(KnowledgeBaseType.USER);
        userBase.setStatus(KnowledgeLifecycleStatus.EMPTY);
        userBase.setActive(true);
        when(bases.selectOne(any())).thenReturn(userBase);
    }

    @Test
    void storesValidatedFileAndCreatesPendingVersion() {
        AtomicLong ids = new AtomicLong(20);
        when(documents.insert(any(KnowledgeDocument.class))).thenAnswer(call -> {
            ((KnowledgeDocument) call.getArgument(0)).setId(ids.incrementAndGet());
            return 1;
        });
        when(versions.insert(any(DocumentVersion.class))).thenAnswer(call -> {
            ((DocumentVersion) call.getArgument(0)).setId(ids.incrementAndGet());
            return 1;
        });
        when(storage.putObject(any(), any(), anyLong(), any()))
                .thenReturn(StoredObject.builder().bucket("knowledge").objectKey("object-key").build());
        MockMultipartFile file = new MockMultipartFile("file", "manual.pdf", "application/pdf",
                "%PDF-1.7\nbody".getBytes(StandardCharsets.US_ASCII));

        var response = service.upload(7L, 11L, file, KEY);

        assertThat(response.documentId()).isEqualTo(21L);
        assertThat(response.versionId()).isEqualTo(22L);
        assertThat(response.processingStage()).isEqualTo("MINERU_QUEUED");
        assertThat(response.status()).isEqualTo(KnowledgeLifecycleStatus.PROCESSING);
        assertThat(response.duplicated()).isFalse();
        verify(storage).putObject(any(), any(), anyLong(), any());
        verify(documents).updateById(any(KnowledgeDocument.class));
        verify(bases).updateById(userBase);
    }

    @Test
    void returnsExistingDocumentBeforeWritingObject() {
        KnowledgeDocument duplicate = new KnowledgeDocument();
        duplicate.setId(31L);
        duplicate.setTitle("manual.md");
        duplicate.setSha256("same");
        duplicate.setStatus(KnowledgeLifecycleStatus.READY);
        DocumentVersion version = new DocumentVersion();
        version.setId(32L);
        version.setDocumentId(31L);
        version.setProcessingStage("PUBLISHED");
        when(documents.selectOne(any())).thenReturn(duplicate);
        when(versions.selectOne(any())).thenReturn(version);

        var response = service.upload(7L, 11L, new MockMultipartFile(
                "file", "manual.md", "text/markdown", "text".getBytes(StandardCharsets.UTF_8)), KEY);

        assertThat(response.documentId()).isEqualTo(31L);
        assertThat(response.duplicated()).isTrue();
        verify(storage, never()).putObject(any(), any(), anyLong(), any());
        verify(documents, never()).insert(any(KnowledgeDocument.class));
    }

    @Test
    void rejectsUploadsIntoVideoSystemKnowledgeBase() {
        userBase.setType(KnowledgeBaseType.VIDEO);
        assertThatThrownBy(() -> service.upload(7L, 11L, new MockMultipartFile(
                "file", "manual.md", "text/markdown", "text".getBytes(StandardCharsets.UTF_8)), KEY))
                .isInstanceOfSatisfying(BizException.class, error -> assertThat(error.getCode()).isEqualTo(409));
        verify(storage, never()).putObject(any(), any(), anyLong(), any());
    }

    @Test
    void rejectsReusingTheSameKeyForDifferentContent() {
        AtomicLong ids = new AtomicLong(40);
        AtomicReference<DocumentUploadIdempotency> saved = new AtomicReference<>();
        when(documents.insert(any(KnowledgeDocument.class))).thenAnswer(call -> {
            ((KnowledgeDocument) call.getArgument(0)).setId(ids.incrementAndGet());
            return 1;
        });
        when(versions.insert(any(DocumentVersion.class))).thenAnswer(call -> {
            ((DocumentVersion) call.getArgument(0)).setId(ids.incrementAndGet());
            return 1;
        });
        when(storage.putObject(any(), any(), anyLong(), any()))
                .thenReturn(StoredObject.builder().bucket("knowledge").objectKey("object-key").build());
        when(idempotency.insert(any(DocumentUploadIdempotency.class))).thenAnswer(call -> {
            saved.set(call.getArgument(0));
            return 1;
        });
        when(idempotency.selectOne(any())).thenAnswer(call -> saved.get());

        service.upload(7L, 11L, new MockMultipartFile(
                "file", "manual.md", "text/markdown", "first".getBytes(StandardCharsets.UTF_8)), KEY);

        assertThatThrownBy(() -> service.upload(7L, 11L, new MockMultipartFile(
                "file", "manual.md", "text/markdown", "second".getBytes(StandardCharsets.UTF_8)), KEY))
                .isInstanceOfSatisfying(BizException.class, error -> {
                    assertThat(error.getCode()).isEqualTo(409);
                    assertThat(error.getMessage()).isEqualTo("IDEMPOTENCY_CONFLICT");
                });
    }
}
