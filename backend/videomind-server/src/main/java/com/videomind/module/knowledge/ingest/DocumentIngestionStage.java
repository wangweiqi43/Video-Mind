package com.videomind.module.knowledge.ingest;

public enum DocumentIngestionStage {
    QUEUED,
    READ_PARSE,
    ENRICH_IMAGES,
    CHUNK_EMBED,
    PUBLISH,
    PUBLISHED;

    public boolean canAdvanceTo(DocumentIngestionStage next) {
        return next != null && next.ordinal() >= ordinal();
    }
}
