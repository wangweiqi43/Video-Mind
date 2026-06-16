package com.videomind.module.knowledge.service;

import com.videomind.module.knowledge.dto.KnowledgeStatusResponse;

public interface KnowledgeService {

    KnowledgeStatusResponse vectorizeTask(Long taskId, Long userId);

    KnowledgeStatusResponse getVectorizeStatus(Long taskId, Long userId);
}

