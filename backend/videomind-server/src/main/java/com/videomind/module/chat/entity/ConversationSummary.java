package com.videomind.module.chat.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("conversation_summary")
public class ConversationSummary {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long conversationId;
    private String summaryText;
    private Long coveredStartMessageId;
    private Long coveredEndMessageId;
    private Integer coveredTurnCount;
    private Integer summaryVersion;
    private String modelName;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
