package com.videomind.module.knowledge.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.videomind.common.enums.TaskStatus;
import com.videomind.module.knowledge.chunk.TextChunker;
import com.videomind.module.knowledge.dto.KnowledgeStatusResponse;
import com.videomind.module.knowledge.embedding.EmbeddingClient;
import com.videomind.module.knowledge.repository.KnowledgeStatusRepository;
import com.videomind.module.knowledge.repository.KnowledgeVectorRepository;
import com.videomind.module.task.entity.TaskRecord;
import com.videomind.module.task.entity.VideoTranscription;
import com.videomind.module.task.mapper.AiSummaryResultMapper;
import com.videomind.module.task.mapper.VideoTranscriptionMapper;
import com.videomind.module.task.service.TaskRecordService;
import java.util.List;
import org.junit.jupiter.api.Test;

class KnowledgeServiceLocalFlowTest {
    @Test
    void legacyVectorizeEndpointAlwaysUsesLocalStorage() {
        TaskRecordService tasks = mock(TaskRecordService.class);
        VideoTranscriptionMapper transcriptions = mock(VideoTranscriptionMapper.class);
        AiSummaryResultMapper summaries = mock(AiSummaryResultMapper.class);
        TextChunker chunker = mock(TextChunker.class);
        EmbeddingClient embeddings = mock(EmbeddingClient.class);
        KnowledgeVectorRepository vectors = mock(KnowledgeVectorRepository.class);
        KnowledgeStatusRepository statuses = mock(KnowledgeStatusRepository.class);
        KnowledgeServiceImpl service = new KnowledgeServiceImpl(
                tasks, transcriptions, summaries, chunker, embeddings, vectors, statuses);
        TaskRecord task = new TaskRecord();
        task.setId(11L);
        task.setUserId(7L);
        task.setVideoId(5L);
        task.setTaskStatus(TaskStatus.SUCCESS);
        VideoTranscription transcript = new VideoTranscription();
        transcript.setTranscriptionText("共享转录文本");
        when(tasks.getTask(11L, 7L)).thenReturn(task);
        when(transcriptions.selectOne(any())).thenReturn(transcript);
        when(chunker.split("共享转录文本")).thenReturn(List.of("共享转录文本"));
        when(embeddings.embed("共享转录文本")).thenReturn(new float[]{1F});
        when(statuses.getStatus(11L)).thenReturn(KnowledgeStatusResponse.builder()
                .taskId(11L).vectorized(true).status("VECTORIZED").chunkCount(1).build());

        KnowledgeStatusResponse result = service.vectorizeTask(11L, 7L);

        assertEquals("VECTORIZED", result.getStatus());
        verify(vectors).saveChunks(eq(11L), any(), any());
    }
}
