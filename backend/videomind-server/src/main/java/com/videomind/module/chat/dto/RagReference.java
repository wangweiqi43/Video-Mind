package com.videomind.module.chat.dto;

import com.videomind.common.enums.KnowledgeChunkType;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class RagReference {

    private String evidenceId;
    private Long knowledgeBaseId;
    private Long documentId;
    private Long documentVersionId;
    private Long videoId;
    private Long taskId;
    private KnowledgeChunkType chunkType;
    private Integer chunkIndex;
    private String chunkText;
    private Double score;
    private String sourceType;
    private String title;
    private String domain;
    private String publishedAt;
    private String url;
    private Integer startSeconds;
    private Integer endSeconds;
}
