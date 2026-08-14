package com.videomind.module.knowledge.deletion;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.videomind.common.enums.KnowledgeBaseType;
import com.videomind.common.enums.KnowledgeLifecycleStatus;
import com.videomind.common.enums.ProcessingTaskType;
import com.videomind.module.knowledge.entity.KnowledgeBase;
import com.videomind.module.knowledge.entity.KnowledgeDocument;
import com.videomind.module.knowledge.mapper.KnowledgeBaseMapper;
import com.videomind.module.knowledge.mapper.KnowledgeDocumentMapper;
import com.videomind.module.task.mq.TaskCreateCommand;
import com.videomind.module.task.service.TaskTargetLifecycle;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class DeletionTargetLifecycle implements TaskTargetLifecycle {
    private final KnowledgeBaseMapper knowledgeBases;
    private final KnowledgeDocumentMapper documents;

    @Override
    public void onTaskCreated(TaskCreateCommand command, LocalDateTime now) {
        if (command.taskType() != ProcessingTaskType.KNOWLEDGE_DELETE) {
            return;
        }
        KnowledgeBase base = knowledgeBases.selectOne(Wrappers.<KnowledgeBase>lambdaQuery()
                .eq(KnowledgeBase::getId, command.businessId())
                .eq(KnowledgeBase::getUserId, command.userId())
                .last("LIMIT 1"));
        if (base == null || base.getType() != KnowledgeBaseType.USER) {
            throw new IllegalStateException("KNOWLEDGE_DELETE_TARGET_INVALID");
        }
        base.setStatus(KnowledgeLifecycleStatus.DELETING);
        base.setActive(false);
        base.setUpdatedTime(now);
        knowledgeBases.updateById(base);
        List<KnowledgeDocument> owned = documents.selectList(Wrappers.<KnowledgeDocument>lambdaQuery()
                .eq(KnowledgeDocument::getKnowledgeBaseId, base.getId())
                .eq(KnowledgeDocument::getUserId, command.userId()));
        for (KnowledgeDocument document : owned) {
            document.setStatus(KnowledgeLifecycleStatus.DELETING);
            document.setActive(false);
            document.setDedupeKey(null);
            document.setUpdatedTime(now);
            documents.updateById(document);
        }
    }
}
