package com.videomind.module.task.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.videomind.module.task.entity.MqConsumeRecord;
import com.videomind.module.task.mapper.MqConsumeRecordMapper;
import com.videomind.module.task.service.ConsumerInboxService;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ConsumerInboxServiceImpl implements ConsumerInboxService {
    private final MqConsumeRecordMapper mapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ClaimResult claim(String consumerGroup, String eventId, Long taskId) {
        LocalDateTime now = LocalDateTime.now();
        MqConsumeRecord candidate = new MqConsumeRecord();
        candidate.setConsumerGroup(consumerGroup);
        candidate.setEventId(eventId);
        candidate.setTaskId(taskId);
        candidate.setConsumeStatus("PROCESSING");
        candidate.setCreatedTime(now);
        candidate.setUpdatedTime(now);
        if (mapper.insertIgnore(candidate) == 1) {
            return new ClaimResult(ClaimStatus.CLAIMED);
        }
        MqConsumeRecord existing = find(consumerGroup, eventId);
        if (existing != null && "COMPLETED".equals(existing.getConsumeStatus())) {
            return new ClaimResult(ClaimStatus.COMPLETED);
        }
        return new ClaimResult(ClaimStatus.IN_PROGRESS);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void complete(String consumerGroup, String eventId) {
        LocalDateTime now = LocalDateTime.now();
        mapper.update(null, Wrappers.<MqConsumeRecord>lambdaUpdate()
                .eq(MqConsumeRecord::getConsumerGroup, consumerGroup)
                .eq(MqConsumeRecord::getEventId, eventId)
                .ne(MqConsumeRecord::getConsumeStatus, "COMPLETED")
                .set(MqConsumeRecord::getConsumeStatus, "COMPLETED")
                .set(MqConsumeRecord::getConsumedTime, now)
                .set(MqConsumeRecord::getUpdatedTime, now));
    }

    private MqConsumeRecord find(String consumerGroup, String eventId) {
        return mapper.selectOne(Wrappers.<MqConsumeRecord>lambdaQuery()
                .eq(MqConsumeRecord::getConsumerGroup, consumerGroup)
                .eq(MqConsumeRecord::getEventId, eventId)
                .last("LIMIT 1"));
    }
}
