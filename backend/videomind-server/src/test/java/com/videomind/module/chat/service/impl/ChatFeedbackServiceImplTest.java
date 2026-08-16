package com.videomind.module.chat.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.videomind.common.enums.MessageRole;
import com.videomind.common.exception.BizException;
import com.videomind.module.chat.dto.ChatFeedbackRequest;
import com.videomind.module.chat.entity.ChatMessage;
import com.videomind.module.chat.entity.ChatMessageFeedback;
import com.videomind.module.chat.mapper.ChatMessageFeedbackMapper;
import com.videomind.module.chat.mapper.ChatMessageMapper;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ChatFeedbackServiceImplTest {
    private final ChatMessageMapper messages = mock(ChatMessageMapper.class);
    private final ChatMessageFeedbackMapper feedback = mock(ChatMessageFeedbackMapper.class);
    private final ChatFeedbackServiceImpl service = new ChatFeedbackServiceImpl(
            messages, feedback, new ObjectMapper());

    @BeforeEach
    void setUp() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, "feedback-message"),
                ChatMessage.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, "feedback-record"),
                ChatMessageFeedback.class);
    }

    @Test
    void storesAThumbsDownAgainstTheExactMessageAndPecGeneration() {
        when(messages.selectOne(any())).thenReturn(assistantMessage());
        when(feedback.selectOne(any())).thenReturn(null);
        ChatFeedbackRequest request = new ChatFeedbackRequest();
        request.setRating("DOWN");
        request.setReasonCodes(List.of("SEMANTIC_DRIFT", "IRRELEVANT_REFERENCE", "SEMANTIC_DRIFT"));
        request.setDetail("  原问题中的专业术语被改写丢失  ");

        var response = service.save(31L, 7L, request);

        ArgumentCaptor<ChatMessageFeedback> inserted = ArgumentCaptor.forClass(ChatMessageFeedback.class);
        verify(feedback).insert(inserted.capture());
        assertThat(inserted.getValue().getMessageId()).isEqualTo(31L);
        assertThat(inserted.getValue().getGenerationId()).isEqualTo(61L);
        assertThat(inserted.getValue().getSessionId()).isEqualTo(51L);
        assertThat(inserted.getValue().getUserId()).isEqualTo(7L);
        assertThat(inserted.getValue().getReasonCodesJson())
                .isEqualTo("[\"SEMANTIC_DRIFT\",\"IRRELEVANT_REFERENCE\"]");
        assertThat(response.getDetail()).isEqualTo("原问题中的专业术语被改写丢失");
    }

    @Test
    void thumbsUpClearsReasonsAndUpdatesTheSingleExistingRecord() {
        when(messages.selectOne(any())).thenReturn(assistantMessage());
        ChatMessageFeedback existing = new ChatMessageFeedback();
        existing.setId(91L);
        existing.setMessageId(31L);
        existing.setGenerationId(61L);
        existing.setSessionId(51L);
        existing.setUserId(7L);
        when(feedback.selectOne(any())).thenReturn(existing);
        ChatFeedbackRequest request = new ChatFeedbackRequest();
        request.setRating("UP");
        request.setReasonCodes(List.of("OTHER"));

        var response = service.save(31L, 7L, request);

        verify(feedback).updateById(existing);
        assertThat(existing.getReasonCodesJson()).isEqualTo("[]");
        assertThat(response.getReasonCodes()).isEmpty();
    }

    @Test
    void thumbsDownRequiresAReasonAndRejectsUnownedOrLegacyMessages() {
        when(messages.selectOne(any())).thenReturn(assistantMessage());
        ChatFeedbackRequest request = new ChatFeedbackRequest();
        request.setRating("DOWN");

        assertThatThrownBy(() -> service.save(31L, 7L, request))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("至少选择一个原因");

        ChatMessage legacy = assistantMessage();
        legacy.setGenerationId(null);
        when(messages.selectOne(any())).thenReturn(legacy);
        assertThatThrownBy(() -> service.save(31L, 7L, request))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("未关联 PEC");
    }

    private ChatMessage assistantMessage() {
        ChatMessage message = new ChatMessage();
        message.setId(31L);
        message.setSessionId(51L);
        message.setUserId(7L);
        message.setGenerationId(61L);
        message.setRole(MessageRole.ASSISTANT);
        return message;
    }
}
