package com.videomind.module.knowledge.ingest;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.videomind.common.enums.KnowledgeLifecycleStatus;
import com.videomind.common.enums.ProcessingTaskType;
import com.videomind.config.AiProperties;
import com.videomind.infrastructure.storage.ObjectStorageService;
import com.videomind.infrastructure.storage.dto.StoredObject;
import com.videomind.module.knowledge.chunk.SemanticChunker;
import com.videomind.module.knowledge.embedding.EmbeddingClient;
import com.videomind.module.knowledge.entity.DocumentAsset;
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
import com.videomind.module.knowledge.retrieval.KnowledgeIndexGateway.IndexedChunk;
import com.videomind.module.knowledge.retrieval.RetrievalCandidate;
import com.videomind.module.task.service.ProcessingTaskHandler;
import com.videomind.module.task.service.TaskCheckpointService;
import com.videomind.module.task.service.TaskCancellationGuard;
import com.videomind.module.task.service.ProcessingTaskStateMachine;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class DocumentIngestionHandler implements ProcessingTaskHandler {
    private final KnowledgeDocumentMapper documents;
    private final DocumentVersionMapper versions;
    private final KnowledgeBaseMapper knowledgeBases;
    private final DocumentChunkMapper chunks;
    private final DocumentAssetMapper assets;
    private final ObjectStorageService storage;
    private final DocumentFileValidator fileValidator;
    private final MineruClient mineru;
    private final SemanticChunker chunker;
    private final EmbeddingClient embeddings;
    private final KnowledgeIndexGateway index;
    private final TaskCheckpointService checkpoints;
    private final AiProperties aiProperties;
    private final ObjectMapper objectMapper;
    private final TaskCancellationGuard cancellation;
    private final ProcessingTaskStateMachine taskState;
    private final DocumentImageEnrichmentService imageEnrichment;

    @Override
    public ProcessingTaskType type() {
        return ProcessingTaskType.DOCUMENT_INGEST;
    }

    @Override
    public String handle(TaskExecutionContext context) throws Exception {
        cancellation.checkProcessingTask(context.taskId());
        Long documentId = context.command().businessId();
        Long versionId = longPayload(context.command().payload(), "versionId");
        KnowledgeDocument document = requireDocument(documentId, context.command().userId());
        DocumentVersion version = requireVersion(versionId, documentId);

        if (!checkpoints.isCompleted(context.taskId(), "PARSED")) {
            advance(context, DocumentIngestionStage.READ_PARSE);
            parse(context.taskId(), document, version);
            cancellation.checkProcessingTask(context.taskId());
            checkpoints.complete(context.taskId(), "PARSED", parsedArtifact(version), rawMarkdownChecksum(version));
        }
        if (!checkpoints.isCompleted(context.taskId(), "IMAGES_ENRICHED")) {
            advance(context, DocumentIngestionStage.ENRICH_IMAGES);
            cancellation.checkProcessingTask(context.taskId());
            String enhanced = imageEnrichment.enrich(document, version);
            checkpoints.complete(context.taskId(), "IMAGES_ENRICHED", enrichedArtifact(version),
                    sha256(enhanced.getBytes(StandardCharsets.UTF_8)));
        }
        String markdown = readMarkdown(version);

        if (!checkpoints.isCompleted(context.taskId(), "CHUNKED")) {
            advance(context, DocumentIngestionStage.CHUNK_EMBED);
            cancellation.checkProcessingTask(context.taskId());
            persistChunks(document, version, DocumentImageEnrichmentService.forEmbedding(markdown));
            checkpoints.complete(context.taskId(), "CHUNKED", "{\"versionId\":" + version.getId() + "}",
                    sha256((version.getId() + ":" + markdown).getBytes(StandardCharsets.UTF_8)));
        }
        List<DocumentChunk> values = versionChunks(version.getId());
        if (values.isEmpty()) {
            throw new IllegalStateException("DOCUMENT_CHUNKS_EMPTY");
        }

        if (!checkpoints.isCompleted(context.taskId(), "INDEXED")) {
            advance(context, DocumentIngestionStage.CHUNK_EMBED);
            cancellation.checkProcessingTask(context.taskId());
            stageIndex(document, version, values);
            checkpoints.complete(context.taskId(), "INDEXED", "{\"chunkCount\":" + values.size() + "}",
                    chunkSetChecksum(values));
        }

        if (!checkpoints.isCompleted(context.taskId(), "PUBLISHED")) {
            advance(context, DocumentIngestionStage.PUBLISH);
            cancellation.checkProcessingTask(context.taskId());
            publish(document, version, values.size());
            checkpoints.complete(context.taskId(), "PUBLISHED", "{\"chunkCount\":" + values.size() + "}",
                    chunkSetChecksum(values));
        }
        return DocumentIngestionStage.PUBLISHED.name();
    }

    private void parse(Long taskId, KnowledgeDocument document, DocumentVersion version) throws Exception {
        byte[] original;
        try (InputStream input = storage.getObject(version.getOriginalBucket(), version.getOriginalObjectKey())) {
            original = input.readAllBytes();
        }
        String markdown;
        String parser;
        List<MineruClient.Asset> parsedAssets = List.of();
        if (fileValidator.mineruRequired(document.getTitle())) {
            Path workspace = Path.of("runtime", "document-ingest", "task-" + taskId).toAbsolutePath().normalize();
            MineruClient.ParseResult result = mineru.parse(original, document.getTitle(), version.getMineruTaskId(),
                    new MineruClient.ParseObserver() {
                        @Override
                        public void submitted(String taskId) {
                            version.setMineruTaskId(taskId);
                            version.setProcessingStage("MINERU_RUNNING");
                            version.setUpdatedTime(LocalDateTime.now());
                            versions.updateById(version);
                        }

                        @Override
                        public void beforePoll(String mineruTaskId) {
                            cancellation.checkProcessingTask(taskId);
                        }
                    }, workspace);
            markdown = result.content();
            parser = result.parser();
            parsedAssets = result.assets();
            version.setMineruTaskId(result.taskId());
        } else {
            markdown = new String(original, StandardCharsets.UTF_8);
            parser = "UTF8_DIRECT";
        }
        cancellation.checkProcessingTask(taskId);
        if (markdown == null || markdown.isBlank()) {
            throw new IllegalStateException("DOCUMENT_MARKDOWN_EMPTY");
        }
        String prefix = derivedPrefix(version.getOriginalObjectKey());
        StoredObject markdownObject = storage.putObject(prefix + "/raw/markdown.md",
                new ByteArrayInputStream(markdown.getBytes(StandardCharsets.UTF_8)),
                markdown.getBytes(StandardCharsets.UTF_8).length, "text/markdown; charset=utf-8");
        for (MineruClient.Asset asset : parsedAssets) {
            String extension = extension(asset.path());
            String contentHash = sha256(asset.bytes());
            StoredObject stored = storage.putObject(prefix + "/assets/" + asset.ordinal() + "-"
                            + contentHash.substring(0, 12) + extension,
                    new ByteArrayInputStream(asset.bytes()), asset.bytes().length, asset.mediaType());
            DocumentAsset value = new DocumentAsset();
            value.setDocumentVersionId(version.getId());
            value.setOrdinalNo(asset.ordinal());
            value.setAssetType(asset.type());
            value.setMediaType(asset.mediaType());
            value.setBucket(stored.getBucket());
            value.setObjectKey(stored.getObjectKey());
            value.setSourcePath(asset.path());
            value.setContentHash(contentHash);
            value.setVisionStatus("PENDING");
            value.setCreatedTime(LocalDateTime.now());
            value.setUpdatedTime(LocalDateTime.now());
            assets.insertIgnore(value);
        }
        version.setRawMarkdownBucket(markdownObject.getBucket());
        version.setRawMarkdownObjectKey(markdownObject.getObjectKey());
        version.setImageCount(parsedAssets.size());
        version.setVisualStatus(parsedAssets.isEmpty() ? "NOT_APPLICABLE" : "PENDING");
        version.setParser(parser);
        version.setProcessingStage("PARSED");
        version.setUpdatedTime(LocalDateTime.now());
        versions.updateById(version);
        deleteWorkspace(taskId);
    }

    private void persistChunks(KnowledgeDocument document, DocumentVersion version, String markdown) {
        SemanticChunker.DocumentChunks result = chunker.chunkDocument(markdown);
        for (SemanticChunker.Chunk child : result.children()) {
            SemanticChunker.ParentChunk parent = result.parents().get(child.parentIndex());
            DocumentChunk value = new DocumentChunk();
            value.setEmbeddingId(sha256((version.getId() + ":" + child.index()).getBytes(StandardCharsets.UTF_8)));
            value.setUserId(document.getUserId());
            value.setKnowledgeBaseId(document.getKnowledgeBaseId());
            value.setDocumentId(document.getId());
            value.setDocumentVersionId(version.getId());
            value.setSourceType("DOCUMENT");
            value.setChunkIndex(child.index());
            value.setParentIndex(child.parentIndex());
            value.setChildIndex(child.childIndex());
            value.setHeading(child.heading());
            value.setContent(child.content());
            value.setParentContent(parent.content());
            value.setStartOffset(child.startOffset());
            value.setEndOffset(child.endOffset());
            value.setPublished(false);
            value.setCreatedTime(LocalDateTime.now());
            chunks.insertIgnore(value);
        }
        version.setProcessingStage("CHUNKED");
        version.setUpdatedTime(LocalDateTime.now());
        versions.updateById(version);
    }

    private void stageIndex(KnowledgeDocument document, DocumentVersion version, List<DocumentChunk> values) {
        List<IndexedChunk> indexed = new ArrayList<>(values.size());
        int dimension = -1;
        for (DocumentChunk value : values) {
            float[] vector = embeddings.embed(value.getContent());
            if (dimension < 0) {
                dimension = vector.length;
            } else if (dimension != vector.length) {
                throw new IllegalStateException("EMBEDDING_DIMENSION_CHANGED_WITHIN_DOCUMENT");
            }
            RetrievalCandidate candidate = new RetrievalCandidate(value.getEmbeddingId(), value.getKnowledgeBaseId(),
                    value.getDocumentId(), value.getDocumentVersionId(), value.getChunkIndex(), value.getParentIndex(),
                    document.getTitle(), value.getHeading(), value.getContent(), value.getParentContent(),
                    value.getStartMs(), value.getEndMs());
            indexed.add(new IndexedChunk(document.getUserId(), candidate, vector, value.getSourceType()));
        }
        index.stage(indexed);
        long staged = index.countVersion(version.getId(), false);
        if (staged != values.size()) {
            throw new IllegalStateException("ELASTICSEARCH_STAGED_COUNT_MISMATCH:" + staged + "/" + values.size());
        }
        version.setEmbeddingModel(aiProperties.getEmbedding().getModel());
        version.setEmbeddingDimension(dimension);
        version.setChunkCount(values.size());
        version.setIndexStatus("STAGED");
        version.setProcessingStage("INDEXED");
        version.setUpdatedTime(LocalDateTime.now());
        versions.updateById(version);
    }

    private void publish(KnowledgeDocument document, DocumentVersion version, int expectedCount) {
        index.publishVersion(version.getId());
        long published = index.countVersion(version.getId(), true);
        if (published != expectedCount) {
            throw new IllegalStateException("ELASTICSEARCH_PUBLISHED_COUNT_MISMATCH:" + published + "/" + expectedCount);
        }
        chunks.publishVersion(version.getId());
        version.setIndexStatus("PUBLISHED");
        version.setProcessingStage("PUBLISHED");
        version.setChunkCount(expectedCount);
        version.setUpdatedTime(LocalDateTime.now());
        versions.updateById(version);
        document.setCurrentVersionId(version.getId());
        document.setStatus(KnowledgeLifecycleStatus.READY);
        document.setFailureCode(null);
        document.setFailureMessage(null);
        document.setUpdatedTime(LocalDateTime.now());
        documents.updateById(document);
        refreshKnowledgeBase(document.getKnowledgeBaseId());
    }

    private void refreshKnowledgeBase(Long knowledgeBaseId) {
        long pending = documents.selectCount(Wrappers.<KnowledgeDocument>lambdaQuery()
                .eq(KnowledgeDocument::getKnowledgeBaseId, knowledgeBaseId)
                .eq(KnowledgeDocument::getActive, true)
                .ne(KnowledgeDocument::getStatus, KnowledgeLifecycleStatus.READY));
        KnowledgeBase knowledgeBase = knowledgeBases.selectById(knowledgeBaseId);
        if (knowledgeBase != null) {
            knowledgeBase.setStatus(pending == 0 ? KnowledgeLifecycleStatus.READY
                    : KnowledgeLifecycleStatus.PROCESSING);
            knowledgeBase.setUpdatedTime(LocalDateTime.now());
            knowledgeBases.updateById(knowledgeBase);
        }
    }

    private KnowledgeDocument requireDocument(Long documentId, Long userId) {
        KnowledgeDocument value = documents.selectOne(Wrappers.<KnowledgeDocument>lambdaQuery()
                .eq(KnowledgeDocument::getId, documentId)
                .eq(KnowledgeDocument::getUserId, userId)
                .eq(KnowledgeDocument::getActive, true)
                .last("LIMIT 1"));
        if (value == null) {
            throw new IllegalStateException("DOCUMENT_NOT_FOUND");
        }
        return value;
    }

    private DocumentVersion requireVersion(Long versionId, Long documentId) {
        DocumentVersion value = versions.selectOne(Wrappers.<DocumentVersion>lambdaQuery()
                .eq(DocumentVersion::getId, versionId)
                .eq(DocumentVersion::getDocumentId, documentId)
                .last("LIMIT 1"));
        if (value == null) {
            throw new IllegalStateException("DOCUMENT_VERSION_NOT_FOUND");
        }
        return value;
    }

    private List<DocumentChunk> versionChunks(Long versionId) {
        return chunks.selectList(Wrappers.<DocumentChunk>lambdaQuery()
                        .eq(DocumentChunk::getDocumentVersionId, versionId)
                        .orderByAsc(DocumentChunk::getChunkIndex)).stream()
                .sorted(Comparator.comparing(DocumentChunk::getChunkIndex)).toList();
    }

    private String readMarkdown(DocumentVersion version) throws Exception {
        if (version.getMarkdownBucket() == null || version.getMarkdownObjectKey() == null) {
            throw new IllegalStateException("DOCUMENT_MARKDOWN_NOT_REGISTERED");
        }
        try (InputStream input = storage.getObject(version.getMarkdownBucket(), version.getMarkdownObjectKey())) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private String rawMarkdownChecksum(DocumentVersion version) throws Exception {
        if (version.getRawMarkdownBucket() == null || version.getRawMarkdownObjectKey() == null) {
            throw new IllegalStateException("DOCUMENT_RAW_MARKDOWN_NOT_REGISTERED");
        }
        try (InputStream input = storage.getObject(version.getRawMarkdownBucket(), version.getRawMarkdownObjectKey())) {
            return sha256(input.readAllBytes());
        }
    }

    private String parsedArtifact(DocumentVersion version) throws Exception {
        return objectMapper.writeValueAsString(Map.of("versionId", version.getId(),
                "bucket", version.getRawMarkdownBucket(), "objectKey", version.getRawMarkdownObjectKey(),
                "parser", version.getParser() == null ? "UNKNOWN" : version.getParser()));
    }

    private String enrichedArtifact(DocumentVersion version) throws Exception {
        return objectMapper.writeValueAsString(Map.of("versionId", version.getId(),
                "bucket", version.getMarkdownBucket(), "objectKey", version.getMarkdownObjectKey(),
                "visualStatus", version.getVisualStatus() == null ? "NOT_APPLICABLE" : version.getVisualStatus()));
    }

    private void advance(TaskExecutionContext context, DocumentIngestionStage next) {
        String currentName = taskState.currentStage(context.taskId());
        DocumentIngestionStage current;
        try {
            current = currentName == null ? DocumentIngestionStage.QUEUED
                    : DocumentIngestionStage.valueOf(currentName);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalStateException("DOCUMENT_STAGE_INVALID:" + currentName);
        }
        if (!current.canAdvanceTo(next)) {
            throw new IllegalStateException("DOCUMENT_STAGE_ROLLBACK:" + current + "->" + next);
        }
        if (!taskState.updateStage(context.taskId(), context.owner(), next.name())) {
            throw new IllegalStateException("DOCUMENT_STAGE_UPDATE_FAILED:" + next);
        }
    }

    private static String chunkSetChecksum(List<DocumentChunk> values) {
        String joined = values.stream().map(DocumentChunk::getEmbeddingId).sorted()
                .reduce("", (left, right) -> left + ":" + right);
        return sha256(joined.getBytes(StandardCharsets.UTF_8));
    }

    private static Long longPayload(Map<String, Object> payload, String key) {
        Object value = payload == null ? null : payload.get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text) {
            return Long.valueOf(text);
        }
        throw new IllegalArgumentException("missing numeric payload field: " + key);
    }

    private static String derivedPrefix(String originalKey) {
        int marker = originalKey.indexOf("/original/");
        return (marker < 0 ? originalKey : originalKey.substring(0, marker)) + "/derived";
    }

    private static String extension(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".png")) {
            return ".png";
        }
        if (lower.endsWith(".webp")) {
            return ".webp";
        }
        return ".jpg";
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static void deleteWorkspace(Long taskId) {
        Path root = Path.of("runtime", "document-ingest").toAbsolutePath().normalize();
        Path target = root.resolve("task-" + taskId).normalize();
        if (!target.startsWith(root) || !Files.exists(target)) return;
        try (Stream<Path> paths = Files.walk(target)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (Exception ignored) { }
            });
        } catch (Exception ignored) { }
    }
}
