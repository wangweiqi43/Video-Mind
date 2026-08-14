package com.videomind.module.knowledge.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.videomind.common.enums.KnowledgeLifecycleStatus;
import com.videomind.common.enums.ProcessingTaskType;
import com.videomind.config.AiProperties;
import com.videomind.infrastructure.storage.ObjectStorageService;
import com.videomind.infrastructure.storage.dto.StoredObject;
import com.videomind.module.knowledge.chunk.SemanticChunker;
import com.videomind.module.knowledge.embedding.EmbeddingClient;
import com.videomind.module.knowledge.entity.DocumentChunk;
import com.videomind.module.knowledge.entity.DocumentVersion;
import com.videomind.module.knowledge.entity.KnowledgeBase;
import com.videomind.module.knowledge.entity.KnowledgeDocument;
import com.videomind.module.knowledge.mapper.DocumentAssetMapper;
import com.videomind.module.knowledge.mapper.DocumentChunkMapper;
import com.videomind.module.knowledge.mapper.DocumentVersionMapper;
import com.videomind.module.knowledge.mapper.KnowledgeBaseMapper;
import com.videomind.module.knowledge.mapper.KnowledgeDocumentMapper;
import com.videomind.module.knowledge.mineru.MineruClient;
import com.videomind.module.knowledge.retrieval.KnowledgeIndexGateway;
import com.videomind.module.task.mq.TaskCreateCommand;
import com.videomind.module.task.service.ProcessingTaskHandler.TaskExecutionContext;
import com.videomind.module.task.service.TaskCheckpointService;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DocumentIngestionHandlerTest {
    private final KnowledgeDocumentMapper documents = mock(KnowledgeDocumentMapper.class);
    private final DocumentVersionMapper versions = mock(DocumentVersionMapper.class);
    private final KnowledgeBaseMapper bases = mock(KnowledgeBaseMapper.class);
    private final DocumentChunkMapper chunks = mock(DocumentChunkMapper.class);
    private final DocumentAssetMapper assets = mock(DocumentAssetMapper.class);
    private final ObjectStorageService storage = mock(ObjectStorageService.class);
    private final MineruClient mineru = mock(MineruClient.class);
    private final EmbeddingClient embeddings = mock(EmbeddingClient.class);
    private final KnowledgeIndexGateway index = mock(KnowledgeIndexGateway.class);
    private final TaskCheckpointService checkpoints = mock(TaskCheckpointService.class);
    private final AiProperties ai = new AiProperties();
    private final List<DocumentChunk> persisted = new ArrayList<>();
    private final KnowledgeDocument document = new KnowledgeDocument();
    private final DocumentVersion version = new DocumentVersion();
    private final DocumentIngestionHandler handler = new DocumentIngestionHandler(documents, versions, bases,
            chunks, assets, storage, new DocumentFileValidator(), mineru, new SemanticChunker(), embeddings,
            index, checkpoints, ai, new ObjectMapper());

    @BeforeEach
    void setUp() {
        document.setId(31L);
        document.setUserId(7L);
        document.setKnowledgeBaseId(11L);
        document.setTitle("manual.md");
        document.setStatus(KnowledgeLifecycleStatus.PROCESSING);
        document.setActive(true);
        version.setId(32L);
        version.setDocumentId(31L);
        version.setOriginalBucket("knowledge");
        version.setOriginalObjectKey("knowledge/7/11/31/v1/original/manual.md");
        when(documents.selectOne(any())).thenReturn(document);
        when(versions.selectOne(any())).thenReturn(version);
        when(storage.getObject(eq("knowledge"), anyString())).thenAnswer(call -> new ByteArrayInputStream(
                "# Install\n\nRun the service.\n\n# Verify\n\nCheck health.".getBytes(StandardCharsets.UTF_8)));
        when(storage.putObject(anyString(), any(), anyLong(), anyString())).thenAnswer(call ->
                StoredObject.builder().bucket("knowledge").objectKey(call.getArgument(0)).build());
        when(chunks.insertIgnore(any(DocumentChunk.class))).thenAnswer(call -> {
            persisted.add(call.getArgument(0));
            return 1;
        });
        when(chunks.selectList(any())).thenAnswer(call -> List.copyOf(persisted));
        when(embeddings.embed(anyString())).thenReturn(new float[] {0.1f, 0.2f});
        when(index.countVersion(32L, false)).thenAnswer(call -> (long) persisted.size());
        when(index.countVersion(32L, true)).thenAnswer(call -> (long) persisted.size());
        when(documents.selectCount(any())).thenReturn(0L);
        KnowledgeBase base = new KnowledgeBase();
        base.setId(11L);
        base.setStatus(KnowledgeLifecycleStatus.PROCESSING);
        when(bases.selectById(11L)).thenReturn(base);
    }

    @Test
    void directTextRunsAllCheckpointedStagesAndPublishesOnlyAfterCountChecks() throws Exception {
        String stage = handler.handle(context());

        assertThat(stage).isEqualTo("PUBLISHED");
        assertThat(persisted).isNotEmpty();
        assertThat(document.getStatus()).isEqualTo(KnowledgeLifecycleStatus.READY);
        assertThat(document.getCurrentVersionId()).isEqualTo(32L);
        assertThat(version.getIndexStatus()).isEqualTo("PUBLISHED");
        verify(mineru, never()).parse(any(), anyString(), any(), any());
        verify(index).publishVersion(32L);
        verify(checkpoints).complete(eq(99L), eq("PARSED"), anyString(), anyString());
        verify(checkpoints).complete(eq(99L), eq("CHUNKED"), anyString(), anyString());
        verify(checkpoints).complete(eq(99L), eq("INDEXED"), anyString(), anyString());
        verify(checkpoints).complete(eq(99L), eq("PUBLISHED"), anyString(), anyString());
    }

    @Test
    void completedParseCheckpointResumesFromStoredMarkdown() throws Exception {
        when(checkpoints.isCompleted(99L, "PARSED")).thenReturn(true);
        version.setMarkdownBucket("knowledge");
        version.setMarkdownObjectKey("derived/markdown.md");

        handler.handle(context());

        verify(storage, never()).putObject(anyString(), any(), anyLong(), anyString());
        verify(mineru, never()).parse(any(), anyString(), any(), any());
        verify(checkpoints, never()).complete(eq(99L), eq("PARSED"), anyString(), anyString());
    }

    @Test
    void pdfUsesLocalMineruAndResumesItsPersistedTaskId() throws Exception {
        document.setTitle("manual.pdf");
        version.setMineruTaskId("mineru-resume-1");
        when(storage.getObject(eq("knowledge"), anyString())).thenAnswer(call -> {
            String key = call.getArgument(1);
            String value = key.contains("/original/") ? "%PDF-1.7" : "# Parsed\n\nMinerU content";
            return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
        });
        when(mineru.parse(any(), eq("manual.pdf"), eq("mineru-resume-1"), any()))
                .thenReturn(new MineruClient.ParseResult("# Parsed\n\nMinerU content", "MINERU_LOCAL",
                        List.of(), "mineru-resume-1"));

        handler.handle(context());

        verify(mineru).parse(any(), eq("manual.pdf"), eq("mineru-resume-1"), any());
        assertThat(version.getParser()).isEqualTo("MINERU_LOCAL");
        assertThat(version.getMineruTaskId()).isEqualTo("mineru-resume-1");
    }

    private static TaskExecutionContext context() {
        TaskCreateCommand command = new TaskCreateCommand(7L, ProcessingTaskType.DOCUMENT_INGEST,
                31L, "DOCUMENT_INGEST:31:v1", "PARSE", 5, Map.of("versionId", 32L));
        return new TaskExecutionContext(99L, "event-1", command);
    }
}
