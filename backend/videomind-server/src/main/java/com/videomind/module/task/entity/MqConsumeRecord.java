package com.videomind.module.task.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("mq_consume_record")
public class MqConsumeRecord {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String consumerGroup;
    private String eventId;
    private Long taskId;
    private String consumeStatus;
    private LocalDateTime consumedTime;
    private LocalDateTime createdTime;
    private LocalDateTime updatedTime;
}
