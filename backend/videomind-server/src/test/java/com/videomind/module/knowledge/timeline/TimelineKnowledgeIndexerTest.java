package com.videomind.module.knowledge.timeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.videomind.common.enums.KnowledgeBaseType;
import com.videomind.common.enums.KnowledgeLifecycleStatus;
import com.videomind.config.AiProperties;
import com.videomind.module.knowledge.embedding.EmbeddingClient;
import com.videomind.module.knowledge.entity.DocumentChunk;
import com.videomind.module.knowledge.entity.DocumentVersion;
import com.videomind.module.knowledge.entity.KnowledgeBase;
import com.videomind.module.knowledge.entity.KnowledgeDocument;
import com.videomind.module.knowledge.mapper.DocumentChunkMapper;
import com.videomind.module.knowledge.mapper.DocumentVersionMapper;
import com.videomind.module.knowledge.mapper.KnowledgeBaseMapper;
import com.videomind.module.knowledge.mapper.KnowledgeDocumentMapper;
import com.videomind.module.knowledge.retrieval.KnowledgeIndexGateway;
import com.videomind.module.knowledge.service.KnowledgeBaseService;
import com.videomind.module.knowledge.timeline.TimelineFusionService.Timeline;
import com.videomind.module.knowledge.timeline.TimelineFusionService.TimelineEvent;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class TimelineKnowledgeIndexerTest {
    @Test
    void projectsEventsWithTimeBoundsAndAtomicallyReplacesOldVersion() {
        KnowledgeBaseService baseService = mock(KnowledgeBaseService.class);
        KnowledgeBaseMapper bases = mock(KnowledgeBaseMapper.class);
        KnowledgeDocumentMapper documents = mock(KnowledgeDocumentMapper.class);
        DocumentVersionMapper versions = mock(DocumentVersionMapper.class);
        DocumentChunkMapper chunks = mock(DocumentChunkMapper.class);
        EmbeddingClient embeddings = mock(EmbeddingClient.class);
        KnowledgeIndexGateway index = mock(KnowledgeIndexGateway.class);
        KnowledgeBase base = new KnowledgeBase();
        base.setId(11L); base.setType(KnowledgeBaseType.VIDEO); base.setStatus(KnowledgeLifecycleStatus.EMPTY);
        when(baseService.ensureVideoKnowledgeBase(7L, 12L, "video")).thenReturn(base);
        when(documents.insert(any(KnowledgeDocument.class))).thenAnswer(call -> {
            ((KnowledgeDocument) call.getArgument(0)).setId(31L); return 1;
        });
        when(versions.insert(any(DocumentVersion.class))).thenAnswer(call -> {
            ((DocumentVersion) call.getArgument(0)).setId(32L); return 1;
        });
        List<DocumentChunk> saved = new ArrayList<>();
        when(chunks.insertIgnore(any(DocumentChunk.class))).thenAnswer(call -> {
            saved.add(call.getArgument(0)); return 1;
        });
        when(chunks.selectList(any())).thenAnswer(call -> List.copyOf(saved));
        when(embeddings.embed(any())).thenReturn(new float[] {0.1f, 0.2f});
        when(index.countVersion(32L, false)).thenReturn(1L);
        when(index.countVersion(32L, true)).thenReturn(1L);
        TimelineKnowledgeIndexer service = new TimelineKnowledgeIndexer(baseService, bases, documents, versions,
                chunks, embeddings, index, new AiProperties());
        var materialized = new VideoTimelineMaterializer.MaterializedTimeline(1L,
                new Timeline(List.of(new TimelineEvent(1_000, 3_000, "speech", List.of("screen")))),
                "# timeline", "bucket", "timeline.md", "events.json");

        var result = service.index(7L, 12L, "video", 2, materialized);

        assertThat(result.chunkCount()).isEqualTo(1);
        assertThat(saved.get(0).getStartMs()).isEqualTo(1_000);
        assertThat(saved.get(0).getEndMs()).isEqualTo(3_000);
        verify(index).publishVersion(32L);
        verify(index).deleteOtherVersions(31L, 32L);
        verify(chunks).unpublishOtherVersions(31L, 32L);
        assertThat(base.getStatus()).isEqualTo(KnowledgeLifecycleStatus.READY);
    }
}
