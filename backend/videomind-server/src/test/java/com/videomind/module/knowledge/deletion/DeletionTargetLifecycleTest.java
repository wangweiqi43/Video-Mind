package com.videomind.module.knowledge.deletion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.videomind.common.enums.KnowledgeBaseType;
import com.videomind.common.enums.KnowledgeLifecycleStatus;
import com.videomind.common.enums.ProcessingTaskType;
import com.videomind.module.knowledge.entity.KnowledgeBase;
import com.videomind.module.knowledge.entity.KnowledgeDocument;
import com.videomind.module.knowledge.mapper.KnowledgeBaseMapper;
import com.videomind.module.knowledge.mapper.KnowledgeDocumentMapper;
import com.videomind.module.task.mq.TaskCreateCommand;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class DeletionTargetLifecycleTest {
    private final KnowledgeBaseMapper knowledgeBases = mock(KnowledgeBaseMapper.class);
    private final KnowledgeDocumentMapper documents = mock(KnowledgeDocumentMapper.class);
    private final DeletionTargetLifecycle lifecycle = new DeletionTargetLifecycle(knowledgeBases, documents);

    @Test
    void marksKnowledgeTreeDeletingInsideTheLocalTaskTransaction() {
        KnowledgeBase base = new KnowledgeBase();
        base.setId(11L);
        base.setUserId(7L);
        base.setType(KnowledgeBaseType.USER);
        KnowledgeDocument document = new KnowledgeDocument();
        document.setId(21L);
        document.setDedupeKey("dedupe");
        when(knowledgeBases.selectOne(any())).thenReturn(base);
        when(documents.selectList(any())).thenReturn(List.of(document));
        TaskCreateCommand command = new TaskCreateCommand(7L, ProcessingTaskType.KNOWLEDGE_DELETE,
                11L, "fingerprint", "DELETE_QUEUED", 10, Map.of());

        lifecycle.onTaskCreated(command, LocalDateTime.parse("2026-08-15T00:00:00"));

        ArgumentCaptor<KnowledgeBase> updatedBase = ArgumentCaptor.forClass(KnowledgeBase.class);
        verify(knowledgeBases).updateById(updatedBase.capture());
        assertThat(updatedBase.getValue().getStatus()).isEqualTo(KnowledgeLifecycleStatus.DELETING);
        assertThat(updatedBase.getValue().getActive()).isFalse();
        ArgumentCaptor<KnowledgeDocument> updatedDocument = ArgumentCaptor.forClass(KnowledgeDocument.class);
        verify(documents).updateById(updatedDocument.capture());
        assertThat(updatedDocument.getValue().getStatus()).isEqualTo(KnowledgeLifecycleStatus.DELETING);
        assertThat(updatedDocument.getValue().getDedupeKey()).isNull();
    }
}
