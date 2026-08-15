package com.videomind.module.agent.audit;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("chat_generation")
public class ChatGeneration {
    @TableId(type = IdType.INPUT)
    private Long id;
    private Long conversationId;
    private Long userId;
    private String clientRequestId;
    private String status;
    private String question;
    private String partialAnswer;
    private String errorCode;
    private String errorMessage;
    private LocalDateTime startedTime;
    private LocalDateTime finishedTime;
    private LocalDateTime createdTime;
    private LocalDateTime updatedTime;
}
