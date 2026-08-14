package com.videomind.module.knowledge.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.videomind.common.enums.KnowledgeBaseType;
import com.videomind.common.enums.KnowledgeLifecycleStatus;
import com.videomind.common.exception.BizException;
import com.videomind.module.knowledge.entity.KnowledgeBase;
import com.videomind.module.knowledge.mapper.KnowledgeBaseMapper;
import com.videomind.module.knowledge.mapper.KnowledgeDocumentMapper;
import com.videomind.module.knowledge.service.impl.KnowledgeBaseServiceImpl;
import java.util.List;
import org.junit.jupiter.api.Test;

class KnowledgeBaseServiceImplTest {
    private final KnowledgeBaseMapper bases = mock(KnowledgeBaseMapper.class);
    private final KnowledgeDocumentMapper documents = mock(KnowledgeDocumentMapper.class);
    private final KnowledgeBaseServiceImpl service = new KnowledgeBaseServiceImpl(bases, documents);

    @Test
    void createsUserKnowledgeBaseAsEmpty() {
        when(bases.insert(any(KnowledgeBase.class))).thenAnswer(invocation -> {
            KnowledgeBase value = invocation.getArgument(0);
            value.setId(9L);
            return 1;
        });
        var response = service.createUserKnowledgeBase(7L, "  项目资料  ");
        assertThat(response.id()).isEqualTo(9L);
        assertThat(response.name()).isEqualTo("项目资料");
        assertThat(response.type()).isEqualTo(KnowledgeBaseType.USER);
        assertThat(response.status()).isEqualTo(KnowledgeLifecycleStatus.EMPTY);
    }

    @Test
    void readyScopeAlwaysContainsVideoKnowledgeBaseFirst() {
        KnowledgeBase video = base(11L, KnowledgeBaseType.VIDEO, KnowledgeLifecycleStatus.READY);
        video.setVideoId(3L);
        KnowledgeBase document = base(12L, KnowledgeBaseType.USER, KnowledgeLifecycleStatus.READY);
        when(bases.selectOne(any())).thenReturn(video);
        when(bases.selectList(any())).thenReturn(List.of(document, video));
        assertThat(service.requireReadyConversationScope(7L, 3L, List.of(12L)))
                .containsExactly(11L, 12L);
    }

    @Test
    void rejectsScopeWhileAnyKnowledgeBaseIsNotReady() {
        KnowledgeBase video = base(11L, KnowledgeBaseType.VIDEO, KnowledgeLifecycleStatus.READY);
        video.setVideoId(3L);
        KnowledgeBase pending = base(12L, KnowledgeBaseType.USER, KnowledgeLifecycleStatus.PROCESSING);
        when(bases.selectOne(any())).thenReturn(video);
        when(bases.selectList(any())).thenReturn(List.of(video, pending));
        assertThatThrownBy(() -> service.requireReadyConversationScope(7L, 3L, List.of(12L)))
                .isInstanceOf(BizException.class).hasMessageContaining("尚未全部就绪");
    }

    @Test
    void videoKnowledgeBaseCannotBeDeletedDirectly() {
        when(bases.selectOne(any())).thenReturn(base(11L, KnowledgeBaseType.VIDEO,
                KnowledgeLifecycleStatus.READY));
        assertThatThrownBy(() -> service.deleteUserKnowledgeBase(7L, 11L))
                .isInstanceOf(BizException.class).hasMessageContaining("不能独立删除");
        verify(bases).selectOne(any());
    }

    private KnowledgeBase base(Long id, KnowledgeBaseType type, KnowledgeLifecycleStatus status) {
        KnowledgeBase value = new KnowledgeBase();
        value.setId(id);
        value.setUserId(7L);
        value.setType(type);
        value.setStatus(status);
        value.setActive(true);
        return value;
    }
}
