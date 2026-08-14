package com.videomind.module.task.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.videomind.module.task.entity.MqConsumeRecord;
import com.videomind.module.task.mapper.MqConsumeRecordMapper;
import com.videomind.module.task.service.ConsumerInboxService.ClaimStatus;
import org.junit.jupiter.api.Test;

class ConsumerInboxServiceImplTest {
    private final MqConsumeRecordMapper mapper = mock(MqConsumeRecordMapper.class);
    private final ConsumerInboxServiceImpl service = new ConsumerInboxServiceImpl(mapper);

    @Test
    void firstDeliveryClaimsUniqueConsumerEventPair() {
        when(mapper.insertIgnore(any(MqConsumeRecord.class))).thenReturn(1);
        assertThat(service.claim("group", "event", 9L).status()).isEqualTo(ClaimStatus.CLAIMED);
    }

    @Test
    void completedDuplicateIsAcknowledgedWithoutBusinessExecution() {
        when(mapper.insertIgnore(any(MqConsumeRecord.class))).thenReturn(0);
        MqConsumeRecord existing = new MqConsumeRecord();
        existing.setConsumeStatus("COMPLETED");
        when(mapper.selectOne(any())).thenReturn(existing);
        assertThat(service.claim("group", "event", 9L).status()).isEqualTo(ClaimStatus.COMPLETED);
    }
}
