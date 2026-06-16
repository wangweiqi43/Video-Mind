package com.videomind.module.knowledge.dto;

import com.videomind.common.enums.KnowledgeChunkType;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class KnowledgeChunk {

    private Long userId;
    private Long videoId;
    private Long taskId;
    private KnowledgeChunkType chunkType;
    private Integer chunkIndex;
    private String chunkText;
}

