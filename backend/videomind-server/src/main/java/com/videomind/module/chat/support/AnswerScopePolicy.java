package com.videomind.module.chat.support;

public final class AnswerScopePolicy {

    public static final String KNOWLEDGE_ONLY = "KNOWLEDGE_ONLY";
    public static final String KNOWLEDGE_EXTENDED = "KNOWLEDGE_EXTENDED";

    private AnswerScopePolicy() {
    }

    public static String normalize(String scope) {
        return KNOWLEDGE_ONLY.equalsIgnoreCase(scope) ? KNOWLEDGE_ONLY : KNOWLEDGE_EXTENDED;
    }

    public static String instruction(String scope) {
        if (KNOWLEDGE_ONLY.equals(normalize(scope))) {
            return """
                    当前回答范围为【仅知识库】。
                    - 只能使用当前视频知识库片段中明确出现的信息作答，禁止使用常识、模型记忆或互联网信息补齐。
                    - “视频提到了某个主题”不等于“视频提供了该主题的一切知识”。例如视频只提到热干面，未讲制作方法时，用户询问制作方法必须明确回答：视频未提供该信息，无法仅基于视频回答。
                    - 如果片段不能直接支持答案，应说明视频实际提到了什么以及缺少什么，不得推测或扩展。
                    """;
        }
        return """
                当前回答范围为【知识库扩展】。
                - 仍然只能使用当前视频知识库，不访问互联网，也不得使用模型自身知识补充视频外事实。
                - 可以对问题进行改写、多查询召回、相邻片段和章节上下文扩展、全文与向量混合检索，并对知识库内容进行解释、归纳和对比。
                - “扩展”只表示检索范围和推理方式增强，不表示互联网搜索。
                - 如果扩展检索后仍无法从知识库获得答案，应明确说明视频未提供该信息。
                """;
    }
}
