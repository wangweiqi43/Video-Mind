package com.videomind.module.chat.generation;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.videomind.module.agent.audit.AgentExecution;
import com.videomind.module.agent.audit.AgentExecutionMapper;
import com.videomind.module.agent.audit.ChatGeneration;
import com.videomind.module.agent.audit.ChatGenerationMapper;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
@Slf4j
public class ChatGenerationRecovery {
    private final ChatGenerationMapper generations;
    private final AgentExecutionMapper executions;

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void recoverInterruptedGenerations() {
        List<ChatGeneration> interrupted = generations.selectList(
                Wrappers.<ChatGeneration>lambdaQuery()
                        .in(ChatGeneration::getStatus, "RUNNING", "CANCEL_REQUESTED"));
        LocalDateTime now = LocalDateTime.now();
        int recovered = 0;
        for (ChatGeneration generation : interrupted) {
            if ("CANCEL_REQUESTED".equals(generation.getStatus())) {
                executions.update(null, Wrappers.<AgentExecution>lambdaUpdate()
                        .eq(AgentExecution::getGenerationId, generation.getId())
                        .set(AgentExecution::getState, "CANCELLED")
                        .set(AgentExecution::getUpdatedTime, now));
                recovered += generations.markCancelled(generation.getId(), generation.getPartialAnswer(), now);
            } else {
                executions.update(null, Wrappers.<AgentExecution>lambdaUpdate()
                        .eq(AgentExecution::getGenerationId, generation.getId())
                        .set(AgentExecution::getState, "FAILED")
                        .set(AgentExecution::getUpdatedTime, now));
                recovered += generations.markFailed(generation.getId(), "SERVER_RESTARTED",
                        "服务重启导致流式回答中断", now);
            }
        }
        if (recovered > 0) {
            log.info("Recovered interrupted chat generations, count={}", recovered);
        }
    }
}
