package com.videomind.module.task.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class VideoKnowledgeReusePolicyTest {
    private final KnowledgeBaseMapper bases = mock(KnowledgeBaseMapper.class);
    private final KnowledgeDocumentMapper documents = mock(KnowledgeDocumentMapper.class);
    private final DocumentVersionMapper versions = mock(DocumentVersionMapper.class);
    private final VideoTimelineMapper timelines = mock(VideoTimelineMapper.class);
    private final KnowledgeIndexGateway index = mock(KnowledgeIndexGateway.class);
    private final AiProperties ai = new AiProperties();
    private final VideoKnowledgeReusePolicy policy = new VideoKnowledgeReusePolicy(
            bases, documents, versions, timelines, index, ai);
    private KnowledgeBase base;
    private DocumentVersion version;

    @BeforeEach
    void readyKnowledgeAssets() {
        ai.getEmbedding().setModel("bge-m3");
        ai.getEmbedding().setDimension(1024);
        base = new KnowledgeBase();
        base.setId(11L);
        base.setType(KnowledgeBaseType.VIDEO);
        base.setStatus(KnowledgeLifecycleStatus.READY);
        base.setActive(true);
        VideoTimeline timeline = new VideoTimeline();
        timeline.setTaskId(31L);
        timeline.setVersionNumber(3);
        timeline.setStatus("READY");
        timeline.setMarkdownObjectKey("knowledge/video/7/5/timeline/v3/timeline.md");
        KnowledgeDocument document = new KnowledgeDocument();
        document.setId(21L);
        document.setStatus(KnowledgeLifecycleStatus.READY);
        document.setCurrentVersionId(22L);
        version = new DocumentVersion();
        version.setId(22L);
        version.setDocumentId(21L);
        version.setVersionNumber(3);
        version.setParser(VideoAnalysisVersions.TIMELINE_PARSER);
        version.setProcessingStage("PUBLISHED");
        version.setIndexStatus("PUBLISHED");
        version.setEmbeddingModel("bge-m3");
        version.setEmbeddingDimension(1024);
        version.setChunkCount(4);
        when(bases.selectOne(any())).thenReturn(base);
        when(timelines.selectOne(any())).thenReturn(timeline);
        when(documents.selectOne(any())).thenReturn(document);
        when(versions.selectById(22L)).thenReturn(version);
        when(index.countVersion(22L, true)).thenReturn(4L);
    }

    @Test
    void reusesOnlyAReadyTimelineWhosePublishedIndexCountMatches() {
        assertThat(policy.isReusable(7L, 5L, 31L, 3)).isTrue();
    }

    @Test
    void rejectsKnowledgeBaseThatIsNotReadyWithoutCallingElasticsearch() {
        base.setStatus(KnowledgeLifecycleStatus.PROCESSING);

        assertThat(policy.isReusable(7L, 5L, 31L, 3)).isFalse();

        verify(index, never()).countVersion(any(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void rejectsCurrentVersionWithStaleEmbeddingSignature() {
        version.setEmbeddingModel("old-model");

        assertThat(policy.isReusable(7L, 5L, 31L, 3)).isFalse();
    }

    @Test
    void rejectsMissingPublishedChunksAndRebuildsWhenElasticsearchIsUnavailable() {
        when(index.countVersion(22L, true)).thenReturn(3L);
        assertThat(policy.isReusable(7L, 5L, 31L, 3)).isFalse();

        when(index.countVersion(22L, true)).thenThrow(new IllegalStateException("offline"));
        assertThat(policy.isReusable(7L, 5L, 31L, 3)).isFalse();
    }
}
