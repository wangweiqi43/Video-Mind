package com.videomind.module.chat.llm;

import com.videomind.module.chat.dto.RagReference;
import com.videomind.module.chat.entity.ChatMessage;
import com.videomind.module.chat.support.AnswerScopePolicy;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@ConditionalOnProperty(prefix = "videomind.ai.chat", name = "mode", havingValue = "mock", matchIfMissing = true)
public class MockChatAnswerClient implements ChatAnswerClient {

    @Override
    public String answer(
            String question,
            List<RagReference> references,
            List<ChatMessage> recentMessages,
            String memorySummary,
            String answerScope
    ) {
        if (references.isEmpty()) {
            return "我暂时没有在已向量化的视频知识库中检索到相关片段。你可以先完成视频解析并点击“加入知识库”，再继续提问。";
        }
        StringBuilder builder = new StringBuilder();
        builder.append("视频内容：").append(shorten(references.get(0).getChunkText(), 220));
        if (AnswerScopePolicy.KNOWLEDGE_EXTENDED.equals(AnswerScopePolicy.normalize(answerScope))) {
            builder.append('\n').append("知识库扩展：Mock 模式展示检索片段；真实模式会扩展当前视频的相关片段和上下文，但不会访问互联网。");
        } else {
            builder.append('\n').append("回答范围：仅使用以上知识库片段，不进行额外检索或互联网扩展。");
        }
        if (StringUtils.hasText(memorySummary)) {
            builder.append('\n').append("结合历史摘要记忆：").append(shorten(memorySummary, 120));
        }
        if (!recentMessages.isEmpty()) {
            builder.append('\n').append("我也参考了最近 ").append(recentMessages.size()).append(" 条会话消息来保持上下文连续。");
        }
        builder.append('\n').append("你的问题是“").append(question).append("”。");
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
