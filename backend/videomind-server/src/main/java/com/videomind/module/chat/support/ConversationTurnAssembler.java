package com.videomind.module.chat.support;

import com.videomind.common.enums.MessageRole;
import com.videomind.module.chat.dto.ConversationTurn;
import com.videomind.module.chat.entity.ChatMessage;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class ConversationTurnAssembler {

    public List<ConversationTurn> assemble(List<ChatMessage> messages) {
        List<ConversationTurn> turns = new ArrayList<>();
        ChatMessage pendingUser = null;
        for (ChatMessage message : messages) {
            if (message.getRole() == MessageRole.USER) {
                pendingUser = message;
            } else if (message.getRole() == MessageRole.ASSISTANT && pendingUser != null) {
                turns.add(ConversationTurn.builder()
                        .userMessageId(pendingUser.getId())
                        .assistantMessageId(message.getId())
                        .question(pendingUser.getContent())
                        .answer(message.getContent())
                        .build());
                pendingUser = null;
            }
        }
        return turns;
    }

    public List<ChatMessage> toMessages(List<ConversationTurn> turns, Long userId) {
        List<ChatMessage> messages = new ArrayList<>(turns.size() * 2);
        for (ConversationTurn turn : turns) {
            messages.add(message(turn.getUserMessageId(), userId, MessageRole.USER, turn.getQuestion()));
            messages.add(message(turn.getAssistantMessageId(), userId, MessageRole.ASSISTANT, turn.getAnswer()));
        }
        return messages;
    }

    private ChatMessage message(Long id, Long userId, MessageRole role, String content) {
        ChatMessage message = new ChatMessage();
        message.setId(id);
        message.setUserId(userId);
        message.setRole(role);
        message.setContent(content);
        return message;
    }
}
