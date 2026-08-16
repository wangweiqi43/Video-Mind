package com.videomind.module.knowledge.service.impl;

import com.videomind.common.enums.KnowledgeLifecycleStatus;
import com.videomind.common.enums.ProcessingTaskType;
import com.videomind.module.knowledge.dto.DocumentUploadResponse;
import com.videomind.module.knowledge.service.DocumentUploadService;
import com.videomind.module.knowledge.service.KnowledgeDocumentApplicationService;
import com.videomind.module.knowledge.mapper.DocumentUploadIdempotencyMapper;
import com.videomind.module.knowledge.entity.DocumentUploadIdempotency;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.videomind.module.task.mq.TaskCreateCommand;
import com.videomind.module.task.mq.TaskDispatchResult;
import com.videomind.module.task.mq.TransactionalTaskMessageProducer;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
@RequiredArgsConstructor
public class KnowledgeDocumentApplicationServiceImpl implements KnowledgeDocumentApplicationService {
    private final DocumentUploadService uploads;
    private final TransactionalTaskMessageProducer messages;
    private final DocumentUploadIdempotencyMapper idempotencyMapper;

    @Override
    public DocumentUploadResponse uploadAndDispatch(Long userId, Long knowledgeBaseId, MultipartFile file,
                                                    String idempotencyKey) {
        // DocumentUploadService is a separate transactional bean. Its database transaction is complete
        // before the RocketMQ half-message starts its own local transaction.
        DocumentUploadResponse registered = uploads.upload(userId, knowledgeBaseId, file, idempotencyKey);
        if (registered.taskId() != null) {
            return registered;
        }
        if (registered.status() == KnowledgeLifecycleStatus.READY) {
            return registered;
        }
        if (registered.versionId() == null) {
            throw new IllegalStateException("DOCUMENT_VERSION_NOT_REGISTERED");
        }
        String fingerprint = "DOCUMENT_INGEST:" + registered.documentId() + ":v" + registered.versionId();
        TaskCreateCommand command = new TaskCreateCommand(userId, ProcessingTaskType.DOCUMENT_INGEST,
                registered.documentId(), fingerprint, "QUEUED", 5,
                Map.of("knowledgeBaseId", knowledgeBaseId, "versionId", registered.versionId()));
        TaskDispatchResult dispatched = messages.dispatch(command);
        DocumentUploadIdempotency idempotency = idempotencyMapper.selectOne(
                Wrappers.<DocumentUploadIdempotency>lambdaQuery()
                        .eq(DocumentUploadIdempotency::getUserId, userId)
                        .eq(DocumentUploadIdempotency::getKnowledgeBaseId, knowledgeBaseId)
                        .eq(DocumentUploadIdempotency::getIdempotencyKey, idempotencyKey).last("LIMIT 1"));
        if (idempotency != null) {
            idempotency.setProcessingTaskId(dispatched.processingTaskId());
            idempotencyMapper.updateById(idempotency);
        }
        return registered.withDispatch(dispatched.eventId(), dispatched.processingTaskId(), dispatched.reused());
    }
}
