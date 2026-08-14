package com.videomind.module.knowledge.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.videomind.common.enums.KnowledgeBaseType;
import com.videomind.common.enums.KnowledgeLifecycleStatus;
import com.videomind.common.exception.BizException;
import com.videomind.module.knowledge.dto.KnowledgeBaseResponse;
import com.videomind.module.knowledge.entity.KnowledgeBase;
import com.videomind.module.knowledge.entity.KnowledgeDocument;
import com.videomind.module.knowledge.mapper.KnowledgeBaseMapper;
import com.videomind.module.knowledge.mapper.KnowledgeDocumentMapper;
import com.videomind.module.knowledge.service.KnowledgeBaseService;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class KnowledgeBaseServiceImpl implements KnowledgeBaseService {
    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final KnowledgeDocumentMapper knowledgeDocumentMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public KnowledgeBaseResponse createUserKnowledgeBase(Long userId, String name) {
        String normalized = normalizeName(name);
        KnowledgeBase value = new KnowledgeBase();
        value.setUserId(userId);
        value.setType(KnowledgeBaseType.USER);
        value.setName(normalized);
        value.setStatus(KnowledgeLifecycleStatus.EMPTY);
        value.setActive(true);
        value.setCreatedTime(LocalDateTime.now());
        value.setUpdatedTime(value.getCreatedTime());
        value.setDeleted(0);
        knowledgeBaseMapper.insert(value);
        return response(value, 0);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public KnowledgeBase ensureVideoKnowledgeBase(Long userId, Long videoId, String videoName) {
        KnowledgeBase existing = findVideoKnowledgeBase(userId, videoId);
        if (existing != null) {
            return existing;
        }
        KnowledgeBase value = new KnowledgeBase();
        value.setUserId(userId);
        value.setType(KnowledgeBaseType.VIDEO);
        value.setVideoId(videoId);
        value.setName(normalizeName(videoName));
        value.setStatus(KnowledgeLifecycleStatus.EMPTY);
        value.setActive(true);
        value.setCreatedTime(LocalDateTime.now());
        value.setUpdatedTime(value.getCreatedTime());
        value.setDeleted(0);
        try {
            knowledgeBaseMapper.insert(value);
            return value;
        } catch (DuplicateKeyException concurrentCreate) {
            KnowledgeBase winner = findVideoKnowledgeBase(userId, videoId);
            if (winner != null) {
                return winner;
            }
            throw concurrentCreate;
        }
    }

    @Override
    public List<KnowledgeBaseResponse> list(Long userId) {
        return knowledgeBaseMapper.selectList(Wrappers.<KnowledgeBase>lambdaQuery()
                        .eq(KnowledgeBase::getUserId, userId)
                        .eq(KnowledgeBase::getActive, true)
                        .orderByDesc(KnowledgeBase::getUpdatedTime))
                .stream().map(value -> response(value, countDocuments(value.getId()))).toList();
    }

    @Override
    public KnowledgeBaseResponse get(Long userId, Long knowledgeBaseId) {
        KnowledgeBase value = requireOwned(userId, knowledgeBaseId);
        return response(value, countDocuments(value.getId()));
    }

    @Override
    public List<Long> requireReadyConversationScope(Long userId, Long videoId,
                                                    List<Long> selectedKnowledgeBaseIds) {
        KnowledgeBase video = findVideoKnowledgeBase(userId, videoId);
        if (video == null) {
            throw new BizException(409, "当前视频尚未创建系统知识库");
        }
        LinkedHashSet<Long> scope = new LinkedHashSet<>();
        scope.add(video.getId());
        if (selectedKnowledgeBaseIds != null) {
            scope.addAll(selectedKnowledgeBaseIds);
        }
        List<KnowledgeBase> values = knowledgeBaseMapper.selectList(Wrappers.<KnowledgeBase>lambdaQuery()
                .eq(KnowledgeBase::getUserId, userId)
                .eq(KnowledgeBase::getActive, true)
                .in(KnowledgeBase::getId, scope));
        if (values.size() != scope.size()) {
            throw new BizException(404, "知识库不存在或无权访问");
        }
        List<Long> unavailable = values.stream()
                .filter(value -> value.getStatus() != KnowledgeLifecycleStatus.READY)
                .map(KnowledgeBase::getId).toList();
        if (!unavailable.isEmpty()) {
            throw new BizException(409, "知识库尚未全部就绪：" + unavailable);
        }
        return List.copyOf(scope);
    }

    private KnowledgeBase requireOwned(Long userId, Long knowledgeBaseId) {
        KnowledgeBase value = knowledgeBaseMapper.selectOne(Wrappers.<KnowledgeBase>lambdaQuery()
                .eq(KnowledgeBase::getId, knowledgeBaseId)
                .eq(KnowledgeBase::getUserId, userId)
                .eq(KnowledgeBase::getActive, true)
                .last("LIMIT 1"));
        if (value == null) {
            throw new BizException(404, "知识库不存在或无权访问");
        }
        return value;
    }

    private KnowledgeBase findVideoKnowledgeBase(Long userId, Long videoId) {
        return knowledgeBaseMapper.selectOne(Wrappers.<KnowledgeBase>lambdaQuery()
                .eq(KnowledgeBase::getUserId, userId)
                .eq(KnowledgeBase::getVideoId, videoId)
                .eq(KnowledgeBase::getType, KnowledgeBaseType.VIDEO)
                .eq(KnowledgeBase::getActive, true)
                .last("LIMIT 1"));
    }

    private long countDocuments(Long knowledgeBaseId) {
        return knowledgeDocumentMapper.selectCount(Wrappers.<KnowledgeDocument>lambdaQuery()
                .eq(KnowledgeDocument::getKnowledgeBaseId, knowledgeBaseId)
                .eq(KnowledgeDocument::getActive, true));
    }

    private KnowledgeBaseResponse response(KnowledgeBase value, long documentCount) {
        return new KnowledgeBaseResponse(value.getId(), value.getType(), value.getVideoId(), value.getName(),
                value.getStatus(), documentCount, value.getCreatedTime(), value.getUpdatedTime());
    }

    private String normalizeName(String name) {
        String normalized = StringUtils.trimWhitespace(name);
        if (!StringUtils.hasText(normalized)) {
            throw new BizException(400, "知识库名称不能为空");
        }
        return normalized.length() > 255 ? normalized.substring(0, 255) : normalized;
    }
}
