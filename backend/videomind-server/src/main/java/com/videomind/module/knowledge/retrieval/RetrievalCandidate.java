package com.videomind.module.knowledge.retrieval;

import java.util.Objects;

public final class RetrievalCandidate {
    private final String embeddingId;
    private final Long knowledgeBaseId;
    private final Long documentId;
    private final Long documentVersionId;
    private final int chunkIndex;
    private final int parentIndex;
    private final String title;
    private final String heading;
    private final String content;
    private final String parentContent;
    private final Long startMs;
    private final Long endMs;
    private final double retrievalScore;

    public RetrievalCandidate(String embeddingId, Long knowledgeBaseId, Long documentId,
            Long documentVersionId, int chunkIndex, int parentIndex, String title, String heading,
            String content, String parentContent, Long startMs, Long endMs) {
        this(embeddingId, knowledgeBaseId, documentId, documentVersionId, chunkIndex, parentIndex,
                title, heading, content, parentContent, startMs, endMs, 0);
    }

    public RetrievalCandidate(String embeddingId, Long knowledgeBaseId, Long documentId,
            Long documentVersionId, int chunkIndex, int parentIndex, String title, String heading,
            String content, String parentContent, Long startMs, Long endMs, double retrievalScore) {
        this.embeddingId = Objects.requireNonNull(embeddingId, "embeddingId");
        this.knowledgeBaseId = knowledgeBaseId;
        this.documentId = documentId;
        this.documentVersionId = documentVersionId;
        this.chunkIndex = chunkIndex;
        this.parentIndex = parentIndex;
        this.title = title;
        this.heading = heading;
        this.content = content;
        this.parentContent = parentContent;
        this.startMs = startMs;
        this.endMs = endMs;
        this.retrievalScore = Double.isFinite(retrievalScore) ? retrievalScore : 0;
    }

    public String embeddingId() {
        return embeddingId;
    }

    /** Stable logical chunk identity shared by BM25, kNN and rewritten-query retrieval. */
    public String chunkId() {
        return embeddingId;
    }

    public Long knowledgeBaseId() {
        return knowledgeBaseId;
    }

    public Long documentId() {
        return documentId;
    }

    public Long documentVersionId() {
        return documentVersionId;
    }

    public int chunkIndex() {
        return chunkIndex;
    }

    public int parentIndex() {
        return parentIndex;
    }

    public String title() {
        return title;
    }

    public String heading() {
        return heading;
    }

    public String content() {
        return content;
    }

    public String parentContent() {
        return parentContent;
    }

    public Long startMs() {
        return startMs;
    }

    public Long endMs() {
        return endMs;
    }

    public double retrievalScore() {
        return retrievalScore;
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof RetrievalCandidate candidate
                && chunkId().equals(candidate.chunkId());
    }

    @Override
    public int hashCode() {
        return chunkId().hashCode();
    }
}
