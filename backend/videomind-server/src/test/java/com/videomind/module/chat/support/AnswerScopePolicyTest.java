package com.videomind.module.chat.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AnswerScopePolicyTest {

    @Test
    void videoOnlyForbidsKnowledgeNotExplicitlyPresentInVideo() {
        String instruction = AnswerScopePolicy.instruction("KNOWLEDGE_ONLY");

        assertThat(instruction)
                .contains("禁止使用常识、模型记忆或互联网信息补齐")
                .contains("视频只提到热干面，未讲制作方法时")
                .contains("无法仅基于视频回答");
    }

    @Test
    void knowledgeExtendedExpandsRetrievalButNeverUsesTheInternet() {
        String instruction = AnswerScopePolicy.instruction("KNOWLEDGE_EXTENDED");

        assertThat(instruction)
                .contains("仍然只能使用当前视频知识库")
                .contains("不访问互联网")
                .contains("检索范围和推理方式增强");
    }
}
