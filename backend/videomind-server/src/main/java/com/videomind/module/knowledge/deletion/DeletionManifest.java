package com.videomind.module.knowledge.deletion;

import java.util.List;

public record DeletionManifest(
        Long userId,
        Long targetId,
        Long knowledgeBaseId,
        Long videoId,
        List<Long> documentIds,
        List<ObjectRef> objects,
        List<Long> conversationIds,
        List<String> uploadIds) {

    public DeletionManifest {
        documentIds = documentIds == null ? List.of() : List.copyOf(documentIds);
        objects = objects == null ? List.of() : List.copyOf(objects);
        conversationIds = conversationIds == null ? List.of() : List.copyOf(conversationIds);
        uploadIds = uploadIds == null ? List.of() : List.copyOf(uploadIds);
    }

    public record ObjectRef(String bucket, String objectKey) { }
}
