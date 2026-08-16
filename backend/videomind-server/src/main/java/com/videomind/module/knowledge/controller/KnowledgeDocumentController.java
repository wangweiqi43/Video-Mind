package com.videomind.module.knowledge.controller;

import com.videomind.common.context.MockUserContext;
import com.videomind.common.exception.BizException;
import com.videomind.infrastructure.storage.ObjectStorageService;
import com.videomind.module.knowledge.entity.DocumentAsset;
import com.videomind.module.knowledge.entity.DocumentVersion;
import com.videomind.module.knowledge.entity.KnowledgeDocument;
import com.videomind.module.knowledge.mapper.DocumentAssetMapper;
import com.videomind.module.knowledge.mapper.DocumentVersionMapper;
import com.videomind.module.knowledge.mapper.KnowledgeDocumentMapper;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/knowledge-documents")
@RequiredArgsConstructor
public class KnowledgeDocumentController {
    private final KnowledgeDocumentMapper documents;
    private final DocumentVersionMapper versions;
    private final DocumentAssetMapper assets;
    private final ObjectStorageService storage;

    @GetMapping("/{documentId}/assets/{assetId}")
    public ResponseEntity<InputStreamResource> asset(@PathVariable Long documentId, @PathVariable Long assetId) {
        KnowledgeDocument document = documents.selectById(documentId);
        DocumentAsset asset = assets.selectById(assetId);
        DocumentVersion version = asset == null ? null : versions.selectById(asset.getDocumentVersionId());
        if (document == null || asset == null || version == null
                || !MockUserContext.currentUserId().equals(document.getUserId())
                || !documentId.equals(version.getDocumentId())) {
            throw new BizException(404, "图片资源不存在或无权访问");
        }
        InputStream input = storage.getObject(asset.getBucket(), asset.getObjectKey());
        MediaType mediaType;
        try { mediaType = MediaType.parseMediaType(asset.getMediaType()); }
        catch (Exception ignored) { mediaType = MediaType.APPLICATION_OCTET_STREAM; }
        return ResponseEntity.ok()
                .contentType(mediaType)
                .eTag('"' + (asset.getContentHash() == null ? String.valueOf(asset.getId()) : asset.getContentHash()) + '"')
                .cacheControl(CacheControl.maxAge(1, TimeUnit.HOURS).cachePrivate().mustRevalidate())
                .body(new InputStreamResource(input));
    }
}
