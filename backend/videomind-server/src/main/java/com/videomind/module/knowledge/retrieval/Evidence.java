package com.videomind.module.knowledge.retrieval;

public record Evidence(
        String evidenceId,
        Long knowledgeBaseId,
        Long documentId,
        Long documentVersionId,
        int chunkIndex,
        int parentIndex,
        String title,
        String heading,
        String content,
        String parentContent,
        Long startMs,
        Long endMs,
        double rrfScore,
        double rerankScore,
        double finalScore) {
}
