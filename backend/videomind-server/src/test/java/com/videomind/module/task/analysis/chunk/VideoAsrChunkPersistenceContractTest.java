package com.videomind.module.task.analysis.chunk;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.annotation.TableName;
import com.videomind.VideoMindApplication;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Arrays;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Update;
import org.mybatis.spring.annotation.MapperScan;
import org.junit.jupiter.api.Test;

class VideoAsrChunkPersistenceContractTest {

    @Test
    void migrationDefinesDurableChunkIdentityAndProviderState() throws Exception {
        try (var input = getClass().getResourceAsStream(
                "/db/migration/V18__durable_video_asr_chunks.sql")) {
            assertThat(input).isNotNull();
            String sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(sql).contains(
                    "CREATE TABLE video_asr_chunk",
                    "UNIQUE KEY uk_video_asr_chunk_task_order (processing_task_id, chunk_index)",
                    "provider_task_id VARCHAR(32)",
                    "result_json JSON");
        }
    }

    @Test
    void mapperUsesUpsertAndCompareAndSetTransitions() throws Exception {
        assertThat(VideoAsrChunk.class.getAnnotation(TableName.class).value())
                .isEqualTo("video_asr_chunk");
        String upsert = VideoAsrChunkMapper.class.getMethod("upsertPlan", VideoAsrChunk.class)
                .getAnnotation(Insert.class).value()[0];
        String claim = VideoAsrChunkMapper.class
                .getMethod("claimSubmission", Long.class, LocalDateTime.class)
                .getAnnotation(Update.class).value()[0];
        assertThat(upsert).contains("ON DUPLICATE KEY UPDATE");
        assertThat(claim).contains("state IN ('PLANNED', 'FAILED')");
    }

    @Test
    void applicationRegistersChunkMapperPackage() {
        MapperScan scan = VideoMindApplication.class.getAnnotation(MapperScan.class);
        assertThat(Arrays.asList(scan.value()))
                .contains("com.videomind.module.task.analysis.chunk");
    }
}
