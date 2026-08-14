package com.videomind.module.task.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("mq_transaction_event")
public class MqTransactionEvent {
    @TableId(type = IdType.INPUT)
    private String eventId;
    private Long taskId;
    private String topic;
    private String tag;
    private String transactionState;
    private String payloadJson;
    private LocalDateTime createdTime;
    private LocalDateTime updatedTime;
}
