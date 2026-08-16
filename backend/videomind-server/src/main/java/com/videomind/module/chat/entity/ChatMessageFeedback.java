package com.videomind.module.chat.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("chat_message_feedback")
public class ChatMessageFeedback {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long messageId;
    private Long generationId;
    private Long sessionId;
    private Long userId;
    private String rating;
    private String reasonCodesJson;
    private String detail;
    private LocalDateTime createdTime;
    private LocalDateTime updatedTime;
}
