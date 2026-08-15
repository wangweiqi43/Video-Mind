package com.videomind.module.chat.generation;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.videomind.module.agent.audit.AgentExecutionMapper;
import com.videomind.module.agent.audit.AgentExecution;
import com.videomind.module.agent.audit.ChatGeneration;
import com.videomind.module.agent.audit.ChatGenerationMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ChatGenerationRecoveryTest {

    @BeforeEach
    void initializeTableMetadata() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, "generation-recovery"),
                ChatGeneration.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, "execution-recovery"),
                AgentExecution.class);
    }

    @Test
    void distinguishesInterruptedAndAlreadyRequestedCancellationOnRestart() {
        ChatGenerationMapper generations = mock(ChatGenerationMapper.class);
        AgentExecutionMapper executions = mock(AgentExecutionMapper.class);
        when(generations.selectList(any())).thenReturn(List.of(
                generation(61L, "RUNNING"), generation(62L, "CANCEL_REQUESTED")));

        new ChatGenerationRecovery(generations, executions).recoverInterruptedGenerations();

        verify(generations).markFailed(eq(61L), eq("SERVER_RESTARTED"), any(), any());
        verify(generations).markCancelled(eq(62L), any(), any());
    }

    private static ChatGeneration generation(Long id, String status) {
        ChatGeneration value = new ChatGeneration();
        value.setId(id);
        value.setStatus(status);
        return value;
    }
}
