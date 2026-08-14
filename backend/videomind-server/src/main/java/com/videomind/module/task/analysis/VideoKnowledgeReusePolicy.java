package com.videomind.module.task.analysis;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.videomind.common.enums.KnowledgeBaseType;
import com.videomind.common.enums.KnowledgeLifecycleStatus;
import com.videomind.config.AiProperties;
import com.videomind.module.knowledge.entity.DocumentVersion;
import com.videomind.module.knowledge.entity.KnowledgeBase;
import com.videomind.module.knowledge.entity.KnowledgeDocument;
import com.videomind.module.knowledge.mapper.DocumentVersionMapper;
import com.videomind.module.knowledge.mapper.KnowledgeBaseMapper;
import com.videomind.module.knowledge.mapper.KnowledgeDocumentMapper;
import com.videomind.module.knowledge.retrieval.KnowledgeIndexGateway;
import com.videomind.module.knowledge.timeline.VideoTimeline;
import com.videomind.module.knowledge.timeline.VideoTimelineMapper;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
@Slf4j
public class VideoKnowledgeReusePolicy {
    private final KnowledgeBaseMapper knowledgeBases;
    private final KnowledgeDocumentMapper documents;
    private final DocumentVersionMapper versions;
    private final VideoTimelineMapper timelines;
    private final KnowledgeIndexGateway index;
    private final AiProperties aiProperties;

    public boolean isReusable(Long userId, Long videoId, Long taskRecordId, Integer transcriptVersion) {
        if (transcriptVersion == null || transcriptVersion < 1) {
            return false;
        }
        KnowledgeBase base = knowledgeBases.selectOne(Wrappers.<KnowledgeBase>lambdaQuery()
                .eq(KnowledgeBase::getUserId, userId)
                .eq(KnowledgeBase::getVideoId, videoId)
                .eq(KnowledgeBase::getType, KnowledgeBaseType.VIDEO)
                .eq(KnowledgeBase::getActive, true)
                .last("LIMIT 1"));
        if (base == null || base.getStatus() != KnowledgeLifecycleStatus.READY) {
            return false;
        }
        VideoTimeline timeline = timelines.selectOne(Wrappers.<VideoTimeline>lambdaQuery()
                .eq(VideoTimeline::getUserId, userId)
                .eq(VideoTimeline::getVideoId, videoId)
                .eq(VideoTimeline::getTaskId, taskRecordId)
                .eq(VideoTimeline::getVersionNumber, transcriptVersion)
                .eq(VideoTimeline::getStatus, "READY")
                .last("LIMIT 1"));
        if (timeline == null || !StringUtils.hasText(timeline.getMarkdownObjectKey())) {
            return false;
        }
        KnowledgeDocument document = documents.selectOne(Wrappers.<KnowledgeDocument>lambdaQuery()
                .eq(KnowledgeDocument::getKnowledgeBaseId, base.getId())
                .eq(KnowledgeDocument::getUserId, userId)
                .eq(KnowledgeDocument::getSourceType, "VIDEO_TIMELINE")
                .eq(KnowledgeDocument::getActive, true)
                .last("LIMIT 1"));
        if (document == null || document.getStatus() != KnowledgeLifecycleStatus.READY
                || document.getCurrentVersionId() == null) {
            return false;
        }
        DocumentVersion version = versions.selectById(document.getCurrentVersionId());
        if (!matchesCurrentVersion(version, document.getId(), transcriptVersion)) {
            return false;
        }
        try {
            return index.countVersion(version.getId(), true) == version.getChunkCount();
        } catch (RuntimeException unavailable) {
            log.warn("Video knowledge reuse check failed; scheduling rebuild, videoId={}, versionId={}, reason={}",
                    videoId, version.getId(), unavailable.getMessage());
            return false;
        }
    }

    private boolean matchesCurrentVersion(DocumentVersion version, Long documentId, Integer transcriptVersion) {
        if (version == null || !Objects.equals(version.getDocumentId(), documentId)
                || !Objects.equals(version.getVersionNumber(), transcriptVersion)
                || !"PUBLISHED".equals(version.getIndexStatus())
                || !"PUBLISHED".equals(version.getProcessingStage())
                || !VideoAnalysisVersions.TIMELINE_PARSER.equals(version.getParser())
                || version.getChunkCount() == null || version.getChunkCount() < 1) {
            return false;
        }
        AiProperties.EmbeddingProvider embedding = aiProperties.getEmbedding();
        return Objects.equals(normalize(version.getEmbeddingModel()), normalize(embedding.getModel()))
                && Objects.equals(version.getEmbeddingDimension(), embedding.getDimension());
    }

    private static String normalize(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
