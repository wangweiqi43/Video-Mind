package com.videomind.module.chat.llm;

import com.videomind.module.chat.dto.RagReference;
import com.videomind.module.chat.entity.ChatMessage;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@ConditionalOnProperty(prefix = "videomind.ai.chat", name = "mode", havingValue = "mock", matchIfMissing = true)
public class MockChatAnswerClient implements ChatAnswerClient {

    @Override
    public String answer(String question, List<RagReference> references, List<ChatMessage> recentMessages, String memorySummary) {
        if (references.isEmpty()) {
            return "我暂时没有在已向量化的视频知识库中检索到相关片段。你可以先完成视频解析并点击“加入知识库”，再继续提问。";
        }
        StringBuilder builder = new StringBuilder();
        builder.append("基于已检索到的视频知识片段，我的回答是：").append('\n');
        builder.append("你的问题是“").append(question).append("”。");
        builder.append("相关视频内容显示：").append(shorten(references.get(0).getChunkText(), 220));
        if (StringUtils.hasText(memorySummary)) {
            builder.append('\n').append("结合历史摘要记忆：").append(shorten(memorySummary, 120));
        }
        if (!recentMessages.isEmpty()) {
            builder.append('\n').append("我也参考了最近 ").append(recentMessages.size()).append(" 条会话消息来保持上下文连续。");
        }
        builder.append('\n').append("当前仍是 Mock 回答，第六阶段已完成 RAG 检索和引用返回，后续可替换为真实大模型。");
        return builder.toString();
    }

    private String shorten(String text, int maxLength) {
        if (!StringUtils.hasText(text)) {
            return "";
        }
        String normalized = text.replace('\n', ' ').trim();
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, maxLength) + "...";
    }
}
