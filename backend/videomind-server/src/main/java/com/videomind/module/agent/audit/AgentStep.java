package com.videomind.module.agent.audit;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("agent_step")
public class AgentStep {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long executionId;
    private Integer stepIndex;
    private String nodeType;
    private String status;
    private String inputJson;
    private String outputJson;
    private LocalDateTime startedTime;
    private LocalDateTime finishedTime;
    private LocalDateTime createdTime;
}
