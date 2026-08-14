package com.videomind.module.knowledge.deletion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.videomind.common.enums.ProcessingTaskType;
import com.videomind.config.UploadProperties;
import com.videomind.infrastructure.storage.ObjectStorageService;
import com.videomind.module.chat.service.ConversationContextService;
import com.videomind.module.knowledge.deletion.DeletionManifest.ObjectRef;
import com.videomind.module.knowledge.retrieval.KnowledgeIndexGateway;
import com.videomind.module.task.entity.TaskCheckpoint;
import com.videomind.module.task.mq.TaskCreateCommand;
import com.videomind.module.task.service.ProcessingTaskHandler.TaskExecutionContext;
import com.videomind.module.task.service.TaskCancellationException;
import com.videomind.module.task.service.TaskCancellationGuard;
import com.videomind.module.task.service.TaskCheckpointService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.data.redis.core.StringRedisTemplate;

class PhysicalDeletionCoordinatorTest {
    private final PhysicalDeletionRepository repository = mock(PhysicalDeletionRepository.class);
    private final KnowledgeIndexGateway index = mock(KnowledgeIndexGateway.class);
    private final ObjectStorageService storage = mock(ObjectStorageService.class);
    private final TaskCheckpointService checkpoints = mock(TaskCheckpointService.class);
    private final TaskCancellationGuard cancellation = mock(TaskCancellationGuard.class);
    private final ConversationContextService contexts = mock(ConversationContextService.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final UploadProperties uploads = new UploadProperties();
    private final PhysicalDeletionCoordinator coordinator = new PhysicalDeletionCoordinator(repository, index,
            storage, checkpoints, cancellation, contexts, mapper, uploads, redis);

    @BeforeEach
    void noCompletedCheckpoints() {
        when(checkpoints.completed(99L)).thenReturn(List.of());
    }

    @Test
    void checkpointsManifestBeforePerformingIdempotentDestructiveActions() throws Exception {
        DeletionManifest manifest = manifest(false);
        when(repository.knowledgeManifest(7L, 11L)).thenReturn(manifest);

        assertThat(coordinator.deleteKnowledge(context(ProcessingTaskType.KNOWLEDGE_DELETE, 11L)))
                .isEqualTo("DELETED");

        InOrder order = inOrder(cancellation, checkpoints, index, storage, repository, contexts);
        order.verify(cancellation).checkProcessingTask(99L);
        order.verify(checkpoints).complete(org.mockito.ArgumentMatchers.eq(99L),
                org.mockito.ArgumentMatchers.eq(PhysicalDeletionCoordinator.DELETION_STARTED),
                anyString(), anyString());
        order.verify(cancellation).checkProcessingTask(99L);
        order.verify(index).deleteKnowledgeBase(11L);
        order.verify(storage).removeObject("docs", "source.pdf");
        order.verify(repository).deleteKnowledgeRows(manifest);
        order.verify(contexts).evictContext(51L);
    }

    @Test
    void resumesVideoDeletionFromManifestAfterExternalCheckpoints() throws Exception {
        DeletionManifest manifest = manifest(true);
        TaskCheckpoint started = new TaskCheckpoint();
        started.setStage(PhysicalDeletionCoordinator.DELETION_STARTED);
        started.setArtifactJson(mapper.writeValueAsString(manifest));
        when(checkpoints.completed(99L)).thenReturn(List.of(started));
        when(checkpoints.isCompleted(99L, "ES_DELETED")).thenReturn(true);
        when(checkpoints.isCompleted(99L, "OBJECTS_DELETED")).thenReturn(true);

        coordinator.deleteVideo(context(ProcessingTaskType.VIDEO_DELETE, 15L));

        verify(index, never()).deleteKnowledgeBase(11L);
        verify(storage, never()).removeObject(anyString(), anyString());
        verify(repository).deleteVideoRows(manifest);
        verify(contexts).evictContext(51L);
        verify(redis).delete("upload:bitmap:upload-1");
        verify(repository, never()).videoManifest(7L, 15L);
    }

    @Test
    void cancellationBeforeManifestPreventsEveryDestructiveAction() {
        doThrow(new TaskCancellationException()).when(cancellation).checkProcessingTask(99L);

        assertThatThrownBy(() -> coordinator.deleteKnowledge(
                context(ProcessingTaskType.KNOWLEDGE_DELETE, 11L)))
                .isInstanceOf(TaskCancellationException.class);

        verify(checkpoints, never()).complete(org.mockito.ArgumentMatchers.anyLong(), anyString(),
                anyString(), anyString());
        verify(index, never()).deleteKnowledgeBase(org.mockito.ArgumentMatchers.anyLong());
        verify(repository, never()).deleteKnowledgeRows(org.mockito.ArgumentMatchers.any());
    }

    private DeletionManifest manifest(boolean video) {
        return new DeletionManifest(7L, video ? 15L : 11L, 11L, video ? 15L : null,
                List.of(21L), List.of(new ObjectRef("docs", "source.pdf")), List.of(51L),
                video ? List.of("upload-1") : List.of());
    }

    private TaskExecutionContext context(ProcessingTaskType type, Long businessId) {
        TaskCreateCommand command = new TaskCreateCommand(7L, type, businessId,
                type + ":7:" + businessId, "DELETE_QUEUED", 10, Map.of());
        return new TaskExecutionContext(99L, "event-1", command);
    }
}
