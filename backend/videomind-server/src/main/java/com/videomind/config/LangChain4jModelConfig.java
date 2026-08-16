package com.videomind.config;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

@Configuration
public class LangChain4jModelConfig {
    public static final String WORKFLOW_MODEL = "workflowLangChain4jModel";
    public static final String CHAT_MODEL = "chatLangChain4jModel";
    public static final String STREAMING_CHAT_MODEL = "streamingChatLangChain4jModel";
    public static final String SUMMARY_MODEL = "summaryLangChain4jModel";

    @Bean(WORKFLOW_MODEL)
    @ConditionalOnProperty(prefix = "videomind.ai.chat", name = "mode", havingValue = "real")
    public ChatModel workflowModel(AiProperties properties, AgentWorkflowProperties workflow) {
        AiProperties.ApiProvider provider = properties.getChat();
        return chatModel(provider, Math.min(30, seconds(workflow.getPlannerTimeoutMillis())));
    }

    @Bean(CHAT_MODEL)
    @ConditionalOnProperty(prefix = "videomind.ai.chat", name = "mode", havingValue = "real")
    public ChatModel chatModel(AiProperties properties, AgentWorkflowProperties workflow) {
        return chatModel(properties.getChat(), Math.min(
                timeoutSeconds(properties.getChat()), seconds(workflow.getExecutorTimeoutMillis())));
    }

    @Bean(STREAMING_CHAT_MODEL)
    @ConditionalOnProperty(prefix = "videomind.ai.chat", name = "mode", havingValue = "real")
    public StreamingChatModel streamingChatModel(AiProperties properties, AgentWorkflowProperties workflow) {
        AiProperties.ApiProvider provider = properties.getChat();
        requireConfigured(provider, "CHAT_LANGCHAIN4J_CONFIGURATION_MISSING");
        return OpenAiStreamingChatModel.builder()
                .baseUrl(provider.getBaseUrl())
                .apiKey(provider.getApiKey())
                .modelName(provider.getModel())
                .timeout(Duration.ofSeconds(Math.min(
                        timeoutSeconds(provider), seconds(workflow.getExecutorTimeoutMillis()))))
                .accumulateToolCallId(false)
                .logRequests(false)
                .logResponses(false)
                .build();
    }

    @Bean(SUMMARY_MODEL)
    @ConditionalOnProperty(prefix = "videomind.ai.summary", name = "mode", havingValue = "real")
    public ChatModel summaryModel(AiProperties properties) {
        return chatModel(properties.getSummary(), timeoutSeconds(properties.getSummary()));
    }

    private ChatModel chatModel(AiProperties.ApiProvider provider, int timeoutSeconds) {
        requireConfigured(provider, "LANGCHAIN4J_CONFIGURATION_MISSING");
        return OpenAiChatModel.builder()
                .baseUrl(provider.getBaseUrl())
                .apiKey(provider.getApiKey())
                .modelName(provider.getModel())
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .maxRetries(0)
                .logRequests(false)
                .logResponses(false)
                .build();
    }

    private static void requireConfigured(AiProperties.ApiProvider provider, String code) {
        if (provider == null || !StringUtils.hasText(provider.getBaseUrl())
                || !StringUtils.hasText(provider.getApiKey()) || !StringUtils.hasText(provider.getModel())) {
            throw new IllegalStateException(code);
        }
    }

    private static int timeoutSeconds(AiProperties.ApiProvider provider) {
        return Math.max(1, provider.getTimeoutSeconds() == null ? 60 : provider.getTimeoutSeconds());
    }

    private static int seconds(long milliseconds) {
        return Math.max(1, (int) Math.ceil(milliseconds / 1_000d));
    }
}
