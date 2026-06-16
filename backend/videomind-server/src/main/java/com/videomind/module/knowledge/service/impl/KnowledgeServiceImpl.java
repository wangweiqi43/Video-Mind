package com.videomind.module.knowledge.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.videomind.common.enums.KnowledgeChunkType;
import com.videomind.common.enums.TaskStatus;
import com.videomind.common.exception.BizException;
import com.videomind.module.knowledge.chunk.TextChunker;
import com.videomind.module.knowledge.dto.KnowledgeChunk;
import com.videomind.module.knowledge.dto.KnowledgeStatusResponse;
import com.videomind.module.knowledge.embedding.EmbeddingClient;
import com.videomind.module.knowledge.repository.KnowledgeStatusRepository;
import com.videomind.module.knowledge.repository.KnowledgeVectorRepository;
import com.videomind.module.knowledge.service.KnowledgeService;
import com.videomind.module.task.entity.AiSummaryResult;
import com.videomind.module.task.entity.TaskRecord;
import com.videomind.module.task.entity.VideoTranscription;
import com.videomind.module.task.mapper.AiSummaryResultMapper;
import com.videomind.module.task.mapper.VideoTranscriptionMapper;
import com.videomind.module.task.service.TaskRecordService;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class KnowledgeServiceImpl implements KnowledgeService {

    private final TaskRecordService taskRecordService;
    private final VideoTranscriptionMapper videoTranscriptionMapper;
    private final AiSummaryResultMapper aiSummaryResultMapper;
    private final TextChunker textChunker;
    private final EmbeddingClient embeddingClient;
    private final KnowledgeVectorRepository knowledgeVectorRepository;
    private final KnowledgeStatusRepository knowledgeStatusRepository;

    @Override
    public KnowledgeStatusResponse vectorizeTask(Long taskId, Long userId) {
        TaskRecord taskRecord = taskRecordService.getTask(taskId, userId);
        if (taskRecord.getTaskStatus() != TaskStatus.SUCCESS) {
            throw new BizException(400, "只有解析成功的任务才能加入知识库");
        }

        VideoTranscription transcription = getTranscription(taskId, userId);
        AiSummaryResult summary = getSummary(taskId, userId);
        List<KnowledgeChunk> chunks = buildChunks(taskRecord, transcription, summary);
        if (chunks.isEmpty()) {
            throw new BizException(400, "该任务没有可向量化的文本内容");
        }

        List<float[]> embeddings = chunks.stream()
                .map(KnowledgeChunk::getChunkText)
                .map(embeddingClient::embed)
                .toList();

        try {
            knowledgeVectorRepository.saveChunks(taskId, chunks, embeddings);
            knowledgeStatusRepository.saveStatus(
                    taskId,
                    true,
                    "VECTORIZED",
                    "已写入知识库向量索引。",
                    chunks.size()
            );
        } catch (Exception ex) {
            knowledgeStatusRepository.saveStatus(
                    taskId,
                    false,
                    "VECTORIZE_FAILED",
                    "向量化失败：" + ex.getMessage(),
                    0
            );
            throw ex;
        }
        return knowledgeStatusRepository.getStatus(taskId);
    }

    @Override
    public KnowledgeStatusResponse getVectorizeStatus(Long taskId, Long userId) {
        taskRecordService.getTask(taskId, userId);
        KnowledgeStatusResponse status = knowledgeStatusRepository.getStatus(taskId);
        long actualCount = knowledgeVectorRepository.countChunks(taskId);
        if (Boolean.TRUE.equals(status.getVectorized()) && actualCount == 0) {
            return KnowledgeStatusResponse.builder()
                    .taskId(taskId)
                    .vectorized(false)
                    .status("VECTOR_INDEX_MISSING")
                    .message("状态记录存在，但未找到向量片段，请重新向量化。")
                    .chunkCount(0)
                    .updatedTime(status.getUpdatedTime())
                    .build();
        }
        if (actualCount > 0 && status.getChunkCount() == null) {
            status.setChunkCount((int) actualCount);
        }
        return status;
    }

    private VideoTranscription getTranscription(Long taskId, Long userId) {
        return videoTranscriptionMapper.selectOne(new LambdaQueryWrapper<VideoTranscription>()
                .eq(VideoTranscription::getTaskId, taskId)
                .eq(VideoTranscription::getUserId, userId));
    }

    private AiSummaryResult getSummary(Long taskId, Long userId) {
        return aiSummaryResultMapper.selectOne(new LambdaQueryWrapper<AiSummaryResult>()
                .eq(AiSummaryResult::getTaskId, taskId)
                .eq(AiSummaryResult::getUserId, userId));
    }

    private List<KnowledgeChunk> buildChunks(TaskRecord taskRecord, VideoTranscription transcription, AiSummaryResult summary) {
        List<KnowledgeChunk> chunks = new ArrayList<>();
        int index = 0;
        if (transcription != null && StringUtils.hasText(transcription.getTranscriptionText())) {
            for (String text : textChunker.split(transcription.getTranscriptionText())) {
                chunks.add(buildChunk(taskRecord, KnowledgeChunkType.TRANSCRIPTION, index++, text));
            }
        }
        if (summary != null && StringUtils.hasText(summary.getSummaryText())) {
            chunks.add(buildChunk(taskRecord, KnowledgeChunkType.SUMMARY, index, summary.getSummaryText()));
        }
        return chunks;
    }

    private KnowledgeChunk buildChunk(TaskRecord taskRecord, KnowledgeChunkType chunkType, int index, String text) {
        return KnowledgeChunk.builder()
                .userId(taskRecord.getUserId())
                .videoId(taskRecord.getVideoId())
                .taskId(taskRecord.getId())
                .chunkType(chunkType)
                .chunkIndex(index)
                .chunkText(text)
                .build();
    }
}
