package com.videomind.module.agent.audit;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("agent_execution")
public class AgentExecution {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long generationId;
    private String profile;
    private String state;
    private String route;
    private Integer toolCalls;
    private Integer replanCount;
    private Long tokenUsage;
    private LocalDateTime deadlineAt;
    private LocalDateTime createdTime;
    private LocalDateTime updatedTime;
}
