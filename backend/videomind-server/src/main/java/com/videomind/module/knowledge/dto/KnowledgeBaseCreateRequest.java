package com.videomind.module.knowledge.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record KnowledgeBaseCreateRequest(
        @NotBlank(message = "知识库名称不能为空")
        @Size(max = 255, message = "知识库名称不能超过255个字符")
        String name) {
}
