package com.videomind.module.knowledge.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.videomind.agentclient.AgentClientProperties;
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
import com.videomind.module.video.entity.VideoFile;
import com.videomind.module.video.service.VideoFileService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class KnowledgeServiceModeIsolationTest {

    private final TaskRecordService tasks = mock(TaskRecordService.class);
    private final VideoTranscriptionMapper transcriptions = mock(VideoTranscriptionMapper.class);
    private final AiSummaryResultMapper summaries = mock(AiSummaryResultMapper.class);
    private final TextChunker chunker = mock(TextChunker.class);
    private final EmbeddingClient embeddings = mock(EmbeddingClient.class);
    private final KnowledgeVectorRepository vectors = mock(KnowledgeVectorRepository.class);
    private final KnowledgeStatusRepository statuses = mock(KnowledgeStatusRepository.class);
    private final VideoFileService videos = mock(VideoFileService.class);
    private final AgentClientProperties agent = new AgentClientProperties();
    private final KnowledgeServiceImpl service = new KnowledgeServiceImpl(
            tasks, transcriptions, summaries, chunker, embeddings, vectors, statuses, agent, videos);

    private TaskRecord task;

    @BeforeEach
    void setUp() {
        agent.setEnabled(true);
        agent.setIngestEnabled(true);
        task = new TaskRecord();
        task.setId(11L);
        task.setUserId(7L);
        task.setVideoId(5L);
        task.setTaskStatus(TaskStatus.SUCCESS);
        when(tasks.getTask(11L, 7L)).thenReturn(task);
    }

    @Test
    void normalModeAlwaysUsesLocalKnowledgeEvenWhenAgentIsEnabled() {
        task.setAnalysisMode("NORMAL");
        VideoTranscription transcription = new VideoTranscription();
        transcription.setTranscriptionText("共享转录文本");
        when(transcriptions.selectOne(any())).thenReturn(transcription);
        when(chunker.split("共享转录文本")).thenReturn(List.of("共享转录文本"));
        when(embeddings.embed("共享转录文本")).thenReturn(new float[] {1F});
        when(statuses.getStatus(11L)).thenReturn(KnowledgeStatusResponse.builder()
                .taskId(11L).vectorized(true).status("VECTORIZED").chunkCount(1).build());

        KnowledgeStatusResponse result = service.vectorizeTask(11L, 7L);

        assertEquals("VECTORIZED", result.getStatus());
        verify(vectors).saveChunks(eq(11L), any(), any());
        verify(videos, never()).getVideoDetail(any(), any());
    }

    @Test
    void advancedModeOnlyReadsAgentKnowledgeStatus() {
        task.setAnalysisMode("ADVANCED");
        VideoFile video = new VideoFile();
        video.setAgentIngestStatus("SUCCESS");
        when(videos.getVideoDetail(5L, 7L)).thenReturn(video);

        KnowledgeStatusResponse result = service.vectorizeTask(11L, 7L);

        assertEquals("AGENT_SUCCESS", result.getStatus());
        verify(vectors, never()).saveChunks(any(), any(), any());
    }
}
