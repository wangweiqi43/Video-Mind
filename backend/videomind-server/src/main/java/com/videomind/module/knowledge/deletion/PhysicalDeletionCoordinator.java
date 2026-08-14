package com.videomind.module.knowledge.deletion;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.videomind.config.UploadProperties;
import com.videomind.infrastructure.storage.ObjectStorageService;
import com.videomind.module.chat.service.ConversationContextService;
import com.videomind.module.knowledge.deletion.DeletionManifest.ObjectRef;
import com.videomind.module.knowledge.retrieval.KnowledgeIndexGateway;
import com.videomind.module.task.entity.TaskCheckpoint;
import com.videomind.module.task.service.TaskCancellationGuard;
import com.videomind.module.task.service.TaskCheckpointService;
import com.videomind.module.task.service.ProcessingTaskHandler.TaskExecutionContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class PhysicalDeletionCoordinator {
    public static final String DELETION_STARTED = "DELETION_STARTED";
    static final String ES_DELETED = "ES_DELETED";
    static final String OBJECTS_DELETED = "OBJECTS_DELETED";
    static final String MYSQL_ROWS_DELETED = "MYSQL_ROWS_DELETED";
    static final String CACHES_EVICTED = "CACHES_EVICTED";
    static final String DELETED = "DELETED";

    private final PhysicalDeletionRepository repository;
    private final KnowledgeIndexGateway index;
    private final ObjectStorageService storage;
    private final TaskCheckpointService checkpoints;
    private final TaskCancellationGuard cancellation;
    private final ConversationContextService conversationContexts;
    private final ObjectMapper objectMapper;
    private final UploadProperties uploadProperties;
    private final StringRedisTemplate redisStack;

    public PhysicalDeletionCoordinator(PhysicalDeletionRepository repository, KnowledgeIndexGateway index,
                                       ObjectStorageService storage, TaskCheckpointService checkpoints,
                                       TaskCancellationGuard cancellation,
                                       ConversationContextService conversationContexts, ObjectMapper objectMapper,
                                       UploadProperties uploadProperties,
                                       @Qualifier("stringRedisTemplate") StringRedisTemplate redisStack) {
        this.repository = repository;
        this.index = index;
        this.storage = storage;
        this.checkpoints = checkpoints;
        this.cancellation = cancellation;
        this.conversationContexts = conversationContexts;
        this.objectMapper = objectMapper;
        this.uploadProperties = uploadProperties;
        this.redisStack = redisStack;
    }

    public String deleteKnowledge(TaskExecutionContext context) throws Exception {
        DeletionManifest manifest = manifest(context, false);
        deleteExternalAndRows(context.taskId(), manifest, false);
        return DELETED;
    }

    public String deleteVideo(TaskExecutionContext context) throws Exception {
        DeletionManifest manifest = manifest(context, true);
        deleteExternalAndRows(context.taskId(), manifest, true);
        return DELETED;
    }

    private DeletionManifest manifest(TaskExecutionContext context, boolean video) throws Exception {
        TaskCheckpoint started = completed(context.taskId(), DELETION_STARTED);
        if (started != null) {
            return objectMapper.readValue(started.getArtifactJson(), DeletionManifest.class);
        }
        cancellation.checkProcessingTask(context.taskId());
        DeletionManifest manifest = video
                ? repository.videoManifest(context.command().userId(), context.command().businessId())
                : repository.knowledgeManifest(context.command().userId(), context.command().businessId());
        String json = objectMapper.writeValueAsString(manifest);
        checkpoints.complete(context.taskId(), DELETION_STARTED, json, sha256(json));
        cancellation.checkProcessingTask(context.taskId());
        return manifest;
    }

    private void deleteExternalAndRows(Long taskId, DeletionManifest manifest, boolean video) throws Exception {
        String checksum = sha256(objectMapper.writeValueAsString(manifest));
        if (!checkpoints.isCompleted(taskId, ES_DELETED)) {
            if (manifest.knowledgeBaseId() != null) {
                index.deleteKnowledgeBase(manifest.knowledgeBaseId());
            }
            completeCount(taskId, ES_DELETED, manifest.documentIds().size(), checksum);
        }
        if (!checkpoints.isCompleted(taskId, OBJECTS_DELETED)) {
            for (ObjectRef object : manifest.objects()) {
                storage.removeObject(object.bucket(), object.objectKey());
            }
            completeCount(taskId, OBJECTS_DELETED, manifest.objects().size(), checksum);
        }
        if (!checkpoints.isCompleted(taskId, MYSQL_ROWS_DELETED)) {
            if (video) {
                repository.deleteVideoRows(manifest);
            } else {
                repository.deleteKnowledgeRows(manifest);
            }
            completeCount(taskId, MYSQL_ROWS_DELETED, manifest.documentIds().size(), checksum);
        }
        if (!checkpoints.isCompleted(taskId, CACHES_EVICTED)) {
            for (Long conversationId : manifest.conversationIds()) {
                conversationContexts.evictContext(conversationId);
            }
            for (String uploadId : manifest.uploadIds()) {
                redisStack.delete(uploadProperties.getBitmapPrefix() + uploadId);
            }
            completeCount(taskId, CACHES_EVICTED,
                    manifest.conversationIds().size() + manifest.uploadIds().size(), checksum);
        }
    }

    private void completeCount(Long taskId, String stage, int count, String checksum) {
        checkpoints.complete(taskId, stage, "{\"count\":" + count + "}", checksum);
    }

    private TaskCheckpoint completed(Long taskId, String stage) {
        return checkpoints.completed(taskId).stream()
                .filter(value -> stage.equals(value.getStage()))
                .findFirst().orElse(null);
    }

    private static String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}
