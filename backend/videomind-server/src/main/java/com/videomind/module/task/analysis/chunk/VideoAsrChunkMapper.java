package com.videomind.module.task.analysis.chunk;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface VideoAsrChunkMapper extends BaseMapper<VideoAsrChunk> {

    @Insert("""
            INSERT INTO video_asr_chunk
                (processing_task_id, task_record_id, video_id, user_id, chunk_index,
                 extraction_start_ms, extraction_end_ms, logical_start_ms, logical_end_ms,
                 engine_signature, state, submit_attempt, created_time, updated_time)
            VALUES
                (#{processingTaskId}, #{taskRecordId}, #{videoId}, #{userId}, #{chunkIndex},
                 #{extractionStartMs}, #{extractionEndMs}, #{logicalStartMs}, #{logicalEndMs},
                 #{engineSignature}, #{state}, #{submitAttempt}, #{createdTime}, #{updatedTime})
            ON DUPLICATE KEY UPDATE
                task_record_id = VALUES(task_record_id), video_id = VALUES(video_id),
                user_id = VALUES(user_id), updated_time = VALUES(updated_time)
            """)
    int upsertPlan(VideoAsrChunk chunk);

    @Update("""
            UPDATE video_asr_chunk
               SET audio_sha256 = #{audioSha256}, updated_time = #{now}
             WHERE processing_task_id = #{processingTaskId} AND chunk_index = #{chunkIndex}
               AND state = 'PLANNED'
               AND (audio_sha256 IS NULL OR audio_sha256 = #{audioSha256})
            """)
    int bindAudioChecksum(@Param("processingTaskId") Long processingTaskId,
                          @Param("chunkIndex") int chunkIndex,
                          @Param("audioSha256") String audioSha256,
                          @Param("now") LocalDateTime now);

    @Update("""
            UPDATE video_asr_chunk
               SET state = 'SUBMITTING', submit_attempt = submit_attempt + 1,
                   provider_task_id = NULL, error_code = NULL, error_message = NULL,
                   updated_time = #{now}
             WHERE id = #{id} AND state IN ('PLANNED', 'FAILED')
               AND audio_sha256 IS NOT NULL
            """)
    int claimSubmission(@Param("id") Long id, @Param("now") LocalDateTime now);

    @Update("""
            UPDATE video_asr_chunk
               SET state = 'SUBMITTED', provider_task_id = #{providerTaskId},
                   submitted_time = #{now}, updated_time = #{now}
             WHERE id = #{id} AND state = 'SUBMITTING'
               AND submit_attempt = #{submitAttempt}
            """)
    int markSubmitted(@Param("id") Long id, @Param("submitAttempt") int submitAttempt,
                      @Param("providerTaskId") String providerTaskId,
                      @Param("now") LocalDateTime now);

    @Update("""
            UPDATE video_asr_chunk
               SET state = 'SUCCEEDED', result_json = #{resultJson},
                   error_code = NULL, error_message = NULL,
                   completed_time = #{now}, updated_time = #{now}
             WHERE id = #{id} AND state = 'SUBMITTED'
               AND provider_task_id = #{providerTaskId}
            """)
    int markSucceeded(@Param("id") Long id, @Param("providerTaskId") String providerTaskId,
                      @Param("resultJson") String resultJson, @Param("now") LocalDateTime now);

    @Update("""
            UPDATE video_asr_chunk
               SET state = 'FAILED', error_code = #{errorCode}, error_message = #{errorMessage},
                   completed_time = #{now}, updated_time = #{now}
             WHERE id = #{id} AND state IN ('SUBMITTING', 'SUBMITTED')
            """)
    int markFailed(@Param("id") Long id, @Param("errorCode") String errorCode,
                   @Param("errorMessage") String errorMessage, @Param("now") LocalDateTime now);

    @Update("""
            UPDATE video_asr_chunk
               SET state = 'FAILED', error_code = 'SUBMISSION_OUTCOME_UNKNOWN',
                   error_message = 'ASR create request outcome was not persisted before timeout',
                   completed_time = #{now}, updated_time = #{now}
             WHERE id = #{id} AND state = 'SUBMITTING' AND updated_time < #{cutoff}
            """)
    int recoverStaleSubmitting(@Param("id") Long id, @Param("cutoff") LocalDateTime cutoff,
                               @Param("now") LocalDateTime now);

    @Update("""
            UPDATE video_asr_chunk
               SET state = 'FAILED', error_code = 'PROVIDER_TASK_EXPIRED',
                   error_message = 'Tencent ASR task id exceeded its 24 hour validity window',
                   completed_time = #{now}, updated_time = #{now}
             WHERE id = #{id} AND state = 'SUBMITTED' AND submitted_time < #{cutoff}
            """)
    int expireSubmitted(@Param("id") Long id, @Param("cutoff") LocalDateTime cutoff,
                        @Param("now") LocalDateTime now);

    @Select("""
            SELECT * FROM video_asr_chunk
             WHERE processing_task_id = #{processingTaskId}
             ORDER BY chunk_index
            """)
    java.util.List<VideoAsrChunk> selectByProcessingTaskId(
            @Param("processingTaskId") Long processingTaskId);
}
