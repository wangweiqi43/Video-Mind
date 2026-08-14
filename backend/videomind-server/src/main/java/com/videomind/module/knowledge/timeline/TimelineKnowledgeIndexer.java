package com.videomind.module.knowledge.timeline;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
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
import com.videomind.module.knowledge.retrieval.KnowledgeIndexGateway.IndexedChunk;
import com.videomind.module.knowledge.retrieval.RetrievalCandidate;
import com.videomind.module.knowledge.service.KnowledgeBaseService;
import com.videomind.module.task.analysis.VideoAnalysisVersions;
import com.videomind.module.knowledge.timeline.TimelineFusionService.TimelineEvent;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class TimelineKnowledgeIndexer {
    private final KnowledgeBaseService knowledgeBaseService;
    private final KnowledgeBaseMapper bases;
    private final KnowledgeDocumentMapper documents;
    private final DocumentVersionMapper versions;
    private final DocumentChunkMapper chunks;
    private final EmbeddingClient embeddings;
    private final KnowledgeIndexGateway index;
    private final AiProperties aiProperties;

    public IndexedTimeline index(Long userId, Long videoId, String videoTitle, int versionNumber,
                                 VideoTimelineMaterializer.MaterializedTimeline timeline) {
        KnowledgeBase knowledgeBase = knowledgeBaseService.ensureVideoKnowledgeBase(userId, videoId, videoTitle);
        KnowledgeDocument document = findOrCreateDocument(userId, videoId, knowledgeBase.getId(), videoTitle,
                sha256(timeline.markdown()));
        DocumentVersion version = findOrCreateVersion(document.getId(), versionNumber, timeline);
        List<DocumentChunk> values = persistChunks(userId, knowledgeBase.getId(), document, version,
                videoTitle, timeline.timeline().events());
        if (values.isEmpty()) {
            throw new IllegalArgumentException("VIDEO_TIMELINE_CHUNKS_EMPTY");
        }
        List<IndexedChunk> indexed = new ArrayList<>();
        int dimension = -1;
        for (DocumentChunk value : values) {
            float[] vector = embeddings.embed(value.getContent());
            dimension = dimension < 0 ? vector.length : dimension;
            if (dimension != vector.length) {
                throw new IllegalStateException("EMBEDDING_DIMENSION_CHANGED_WITHIN_TIMELINE");
            }
            RetrievalCandidate candidate = new RetrievalCandidate(value.getEmbeddingId(), value.getKnowledgeBaseId(),
                    value.getDocumentId(), value.getDocumentVersionId(), value.getChunkIndex(), value.getParentIndex(),
                    videoTitle, value.getHeading(), value.getContent(), value.getParentContent(),
                    value.getStartMs(), value.getEndMs());
            indexed.add(new IndexedChunk(userId, candidate, vector, "VIDEO_TIMELINE"));
        }
        index.stage(indexed);
        requireCount(version.getId(), false, values.size(), "STAGED");
        index.publishVersion(version.getId());
        requireCount(version.getId(), true, values.size(), "PUBLISHED");
        index.deleteOtherVersions(document.getId(), version.getId());
        chunks.unpublishOtherVersions(document.getId(), version.getId());
        chunks.publishVersion(version.getId());

        version.setIndexStatus("PUBLISHED");
        version.setProcessingStage("PUBLISHED");
        version.setEmbeddingModel(aiProperties.getEmbedding().getModel());
        version.setEmbeddingDimension(dimension);
        version.setChunkCount(values.size());
        version.setUpdatedTime(LocalDateTime.now());
        versions.updateById(version);
        document.setCurrentVersionId(version.getId());
        document.setSha256(sha256(timeline.markdown()));
        document.setStatus(KnowledgeLifecycleStatus.READY);
        document.setUpdatedTime(LocalDateTime.now());
        documents.updateById(document);
        knowledgeBase.setStatus(KnowledgeLifecycleStatus.READY);
        knowledgeBase.setUpdatedTime(LocalDateTime.now());
        bases.updateById(knowledgeBase);
        return new IndexedTimeline(knowledgeBase.getId(), document.getId(), version.getId(), values.size());
    }

    private KnowledgeDocument findOrCreateDocument(Long userId, Long videoId, Long knowledgeBaseId,
                                                   String title, String checksum) {
        String dedupe = sha256("video:" + videoId + ":timeline");
        KnowledgeDocument value = documents.selectOne(Wrappers.<KnowledgeDocument>lambdaQuery()
                .eq(KnowledgeDocument::getKnowledgeBaseId, knowledgeBaseId)
                .eq(KnowledgeDocument::getDedupeKey, dedupe)
                .last("LIMIT 1"));
        if (value != null) {
            value.setStatus(KnowledgeLifecycleStatus.PROCESSING);
            value.setUpdatedTime(LocalDateTime.now());
            documents.updateById(value);
            return value;
        }
        value = new KnowledgeDocument();
        value.setKnowledgeBaseId(knowledgeBaseId);
        value.setUserId(userId);
        value.setSourceType("VIDEO_TIMELINE");
        value.setTitle(title + " · 时间轴");
        value.setSha256(checksum);
        value.setDedupeKey(dedupe);
        value.setStatus(KnowledgeLifecycleStatus.PROCESSING);
        value.setActive(true);
        value.setCreatedTime(LocalDateTime.now());
        value.setUpdatedTime(value.getCreatedTime());
        value.setDeleted(0);
        documents.insert(value);
        return value;
    }

    private DocumentVersion findOrCreateVersion(Long documentId, int versionNumber,
                                                VideoTimelineMaterializer.MaterializedTimeline timeline) {
        DocumentVersion value = versions.selectOne(Wrappers.<DocumentVersion>lambdaQuery()
                .eq(DocumentVersion::getDocumentId, documentId)
                .eq(DocumentVersion::getVersionNumber, versionNumber)
                .last("LIMIT 1"));
        if (value != null) {
            return value;
        }
        value = new DocumentVersion();
        value.setDocumentId(documentId);
        value.setVersionNumber(versionNumber);
        value.setOriginalBucket(timeline.bucket());
        value.setOriginalObjectKey(timeline.markdownObjectKey());
        value.setOriginalFileSize((long) timeline.markdown().getBytes(StandardCharsets.UTF_8).length);
        value.setOriginalContentType("text/markdown; charset=utf-8");
        value.setMarkdownBucket(timeline.bucket());
        value.setMarkdownObjectKey(timeline.markdownObjectKey());
        value.setParser(VideoAnalysisVersions.TIMELINE_PARSER);
        value.setProcessingStage("CHUNKING");
        value.setIndexStatus("PENDING");
        value.setChunkCount(0);
        value.setCreatedTime(LocalDateTime.now());
        value.setUpdatedTime(value.getCreatedTime());
        versions.insert(value);
        return value;
    }

    private List<DocumentChunk> persistChunks(Long userId, Long knowledgeBaseId, KnowledgeDocument document,
                                              DocumentVersion version, String title,
                                              List<TimelineEvent> events) {
        int chunkIndex = 0;
        for (TimelineEvent event : events) {
            String content = content(event);
            DocumentChunk value = new DocumentChunk();
            value.setEmbeddingId(sha256(version.getId() + ":timeline:" + chunkIndex));
            value.setUserId(userId);
            value.setKnowledgeBaseId(knowledgeBaseId);
            value.setDocumentId(document.getId());
            value.setDocumentVersionId(version.getId());
            value.setSourceType("VIDEO_TIMELINE");
            value.setChunkIndex(chunkIndex);
            value.setParentIndex(chunkIndex);
            value.setChildIndex(0);
            value.setHeading(TimelineFusionService.format(event.startMs()) + " - "
                    + TimelineFusionService.format(event.endMs()));
            value.setContent(content);
            value.setParentContent("# " + title + "\n\n" + content);
            value.setStartMs(event.startMs());
            value.setEndMs(event.endMs());
            value.setPublished(false);
            value.setCreatedTime(LocalDateTime.now());
            chunks.insertIgnore(value);
            chunkIndex++;
        }
        return chunks.selectList(Wrappers.<DocumentChunk>lambdaQuery()
                .eq(DocumentChunk::getDocumentVersionId, version.getId())
                .orderByAsc(DocumentChunk::getChunkIndex));
    }

    private void requireCount(Long versionId, boolean published, int expected, String phase) {
        long actual = index.countVersion(versionId, published);
        if (actual != expected) {
            throw new IllegalStateException("TIMELINE_" + phase + "_COUNT_MISMATCH:" + actual + "/" + expected);
        }
    }

    private static String content(TimelineEvent event) {
        StringBuilder value = new StringBuilder();
        if (!event.speechText().isBlank()) {
            value.append("语音：").append(event.speechText());
        }
        if (!event.visualTexts().isEmpty()) {
            if (!value.isEmpty()) {
                value.append("\n");
            }
            value.append("画面文字：").append(String.join("；", event.visualTexts()));
        }
        return value.toString();
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public record IndexedTimeline(Long knowledgeBaseId, Long documentId, Long versionId, int chunkCount) {
    }
}
