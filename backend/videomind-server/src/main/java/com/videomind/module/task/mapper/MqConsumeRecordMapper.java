package com.videomind.module.task.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.videomind.module.task.entity.MqConsumeRecord;
import org.apache.ibatis.annotations.Insert;

public interface MqConsumeRecordMapper extends BaseMapper<MqConsumeRecord> {
    @Insert("""
            INSERT IGNORE INTO mq_consume_record
                (consumer_group, event_id, task_id, consume_status, created_time, updated_time)
            VALUES
                (#{consumerGroup}, #{eventId}, #{taskId}, #{consumeStatus}, #{createdTime}, #{updatedTime})
            """)
    int insertIgnore(MqConsumeRecord record);
}
