package com.videomind.module.knowledge.ingest;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.videomind.infrastructure.storage.ObjectStorageService;
import com.videomind.infrastructure.storage.dto.StoredObject;
import com.videomind.module.knowledge.entity.DocumentAsset;
import com.videomind.module.knowledge.entity.DocumentVersion;
import com.videomind.module.knowledge.entity.KnowledgeDocument;
import com.videomind.module.knowledge.mapper.DocumentAssetMapper;
import com.videomind.module.knowledge.mapper.DocumentVersionMapper;
import com.videomind.module.knowledge.vision.VisionClient;
import com.videomind.module.knowledge.vision.VisionClient.VisionResult;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class DocumentImageEnrichmentService {
    private static final Pattern IMAGE = Pattern.compile("!\\[([^]]*)]\\(([^)]+)\\)");
    private final DocumentAssetMapper assets;
    private final DocumentVersionMapper versions;
    private final ObjectStorageService storage;
    private final VisionClient vision;
    private final ObjectMapper objectMapper;
    private final Executor executor;

    public DocumentImageEnrichmentService(DocumentAssetMapper assets, DocumentVersionMapper versions,
                                          ObjectStorageService storage, VisionClient vision,
                                          ObjectMapper objectMapper,
                                          @Qualifier("documentVisionExecutor") Executor executor) {
        this.assets = assets;
        this.versions = versions;
        this.storage = storage;
        this.vision = vision;
        this.objectMapper = objectMapper;
        this.executor = executor;
    }

    public String enrich(KnowledgeDocument document, DocumentVersion version) throws Exception {
        String markdown = read(version.getRawMarkdownBucket(), version.getRawMarkdownObjectKey());
        List<DocumentAsset> values = assets.selectList(Wrappers.<DocumentAsset>lambdaQuery()
                .eq(DocumentAsset::getDocumentVersionId, version.getId()).orderByAsc(DocumentAsset::getOrdinalNo));
        List<CompletableFuture<Void>> pending = new ArrayList<>();
        for (DocumentAsset asset : values) {
            if ("READY".equals(asset.getVisionStatus()) || "DEGRADED".equals(asset.getVisionStatus())) continue;
            pending.add(CompletableFuture.runAsync(() -> describe(asset), executor));
        }
        CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new)).join();
        values = assets.selectList(Wrappers.<DocumentAsset>lambdaQuery()
                .eq(DocumentAsset::getDocumentVersionId, version.getId()).orderByAsc(DocumentAsset::getOrdinalNo));
        String enhanced = rewrite(markdown, document.getId(), values);
        String prefix = derivedPrefix(version.getOriginalObjectKey());
        byte[] finalBytes = enhanced.getBytes(StandardCharsets.UTF_8);
        StoredObject finalObject = storage.putObject(prefix + "/markdown.md",
                new ByteArrayInputStream(finalBytes), finalBytes.length, "text/markdown; charset=utf-8");
        Map<String, Object> manifest = Map.of("schemaVersion", "document-assets-v1", "documentId", document.getId(),
                "versionId", version.getId(), "assets", values.stream().map(value -> Map.of(
                        "assetId", value.getId(), "sourcePath", value.getSourcePath(),
                        "contentHash", value.getContentHash(), "visionStatus", value.getVisionStatus(),
                        "description", value.getDescription() == null ? "" : value.getDescription())).toList());
        byte[] manifestBytes = objectMapper.writeValueAsBytes(manifest);
        StoredObject manifestObject = storage.putObject(prefix + "/manifest.json",
                new ByteArrayInputStream(manifestBytes), manifestBytes.length, "application/json");
        int described = (int) values.stream().filter(value -> "READY".equals(value.getVisionStatus())).count();
        version.setMarkdownBucket(finalObject.getBucket());
        version.setMarkdownObjectKey(finalObject.getObjectKey());
        version.setManifestBucket(manifestObject.getBucket());
        version.setManifestObjectKey(manifestObject.getObjectKey());
        version.setImageCount(values.size());
        version.setDescribedImageCount(described);
        version.setVisualStatus(values.isEmpty() ? "NOT_APPLICABLE"
                : described == values.size() ? "READY" : "DEGRADED");
        version.setUpdatedTime(LocalDateTime.now());
        versions.updateById(version);
        return enhanced;
    }

    private void describe(DocumentAsset asset) {
        try (InputStream input = storage.getObject(asset.getBucket(), asset.getObjectKey())) {
            VisionResult result = vision.describe(input.readAllBytes(), asset.getMediaType());
            asset.setDescription(result.success() ? result.description() : null);
            asset.setVisionStatus(result.success() ? "READY" : "DEGRADED");
            asset.setVisionModel(result.model());
            asset.setVisionErrorCode(result.errorCode());
        } catch (Exception failure) {
            asset.setDescription(null);
            asset.setVisionStatus("DEGRADED");
            asset.setVisionErrorCode("VISION_ASSET_READ_FAILED");
        }
        asset.setUpdatedTime(LocalDateTime.now());
        assets.updateById(asset);
    }

    static String rewrite(String markdown, Long documentId, List<DocumentAsset> assets) {
        Map<String, DocumentAsset> exact = new HashMap<>();
        Map<String, List<DocumentAsset>> basename = new HashMap<>();
        for (DocumentAsset asset : assets) {
            String path = normalize(asset.getSourcePath());
            exact.put(path, asset);
            basename.computeIfAbsent(baseName(path), ignored -> new ArrayList<>()).add(asset);
        }
        Matcher matcher = IMAGE.matcher(markdown);
        StringBuffer output = new StringBuffer();
        while (matcher.find()) {
            String source = normalize(matcher.group(2));
            DocumentAsset asset = exact.get(source);
            if (asset == null) {
                List<DocumentAsset> candidates = basename.get(baseName(source));
                if (candidates != null && candidates.size() == 1) asset = candidates.get(0);
            }
            String replacement;
            if (asset == null) {
                replacement = "[该图片识别失败]";
            } else {
                String description = "READY".equals(asset.getVisionStatus()) && StringUtils.hasText(asset.getDescription())
                        ? markdownAltText(asset.getDescription()) : "该图片识别失败";
                replacement = "![" + description + "](/api/knowledge-documents/" + documentId
                        + "/assets/" + asset.getId() + ")";
            }
            matcher.appendReplacement(output, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(output);
        return output.toString();
    }

    public static String forEmbedding(String markdown) {
        return IMAGE.matcher(markdown).replaceAll("图片说明：$1");
    }

    private static String markdownAltText(String description) {
        return description.replaceAll("\\s+", " ").trim()
                .replace("[", "（")
                .replace("]", "）");
    }

    private String read(String bucket, String key) throws Exception {
        if (!StringUtils.hasText(bucket) || !StringUtils.hasText(key)) {
            throw new IllegalStateException("DOCUMENT_RAW_MARKDOWN_NOT_REGISTERED");
        }
        try (InputStream input = storage.getObject(bucket, key)) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String normalize(String value) {
        String result = value == null ? "" : value.trim().replace('\\', '/');
        while (result.startsWith("./")) result = result.substring(2);
        return result.toLowerCase(Locale.ROOT);
    }

    private static String baseName(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }

    private static String derivedPrefix(String originalKey) {
        int marker = originalKey.indexOf("/original/");
        return (marker < 0 ? originalKey : originalKey.substring(0, marker)) + "/derived";
    }
}
