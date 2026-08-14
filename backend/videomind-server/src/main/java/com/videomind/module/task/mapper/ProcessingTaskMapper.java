package com.videomind.module.task.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.videomind.module.task.entity.ProcessingTask;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Update;

public interface ProcessingTaskMapper extends BaseMapper<ProcessingTask> {
    @Insert("""
            INSERT IGNORE INTO processing_task
                (id, event_id, user_id, task_type, business_id, business_fingerprint,
                 active_fingerprint, state, stage, state_version, attempt_count, max_attempts,
                 replay_generation, created_time, updated_time)
            VALUES
                (#{id}, #{eventId}, #{userId}, #{taskType}, #{businessId}, #{businessFingerprint},
                 #{activeFingerprint}, #{state}, #{stage}, #{stateVersion}, #{attemptCount}, #{maxAttempts},
                 #{replayGeneration}, #{createdTime}, #{updatedTime})
            """)
    int insertIgnoreActive(ProcessingTask task);

    @Update("""
            UPDATE processing_task
               SET business_id = #{businessId}, updated_time = #{now}
             WHERE id = #{taskId} AND business_id = #{expectedBusinessId}
            """)
    int bindBusinessId(@Param("taskId") Long taskId,
                       @Param("expectedBusinessId") Long expectedBusinessId,
                       @Param("businessId") Long businessId,
                       @Param("now") LocalDateTime now);

    @Update("""
            UPDATE processing_task
               SET state = 'PROCESSING', stage = #{stage}, lease_owner = #{owner},
                   lease_expires_at = #{expiresAt}, attempt_count = attempt_count + 1,
                   state_version = state_version + 1,
                   started_time = COALESCE(started_time, #{now}), updated_time = #{now}
             WHERE id = #{taskId} AND state_version = #{expectedVersion}
               AND state IN ('PENDING', 'RETRY_WAIT', 'PROCESSING')
               AND attempt_count < max_attempts
               AND (state <> 'RETRY_WAIT' OR next_retry_at IS NULL OR next_retry_at <= #{now})
               AND (state <> 'PROCESSING' OR lease_expires_at IS NULL
                    OR lease_expires_at < #{now} OR lease_owner = #{owner})
            """)
    int acquireLease(@Param("taskId") Long taskId, @Param("expectedVersion") long expectedVersion,
                     @Param("owner") String owner, @Param("stage") String stage,
                     @Param("now") LocalDateTime now, @Param("expiresAt") LocalDateTime expiresAt);

    @Update("""
            UPDATE processing_task
               SET lease_expires_at = #{expiresAt}, state_version = state_version + 1,
                   updated_time = #{now}
             WHERE id = #{taskId} AND state = 'PROCESSING'
               AND state_version = #{expectedVersion} AND lease_owner = #{owner}
               AND lease_expires_at >= #{now}
            """)
    int renewLease(@Param("taskId") Long taskId, @Param("expectedVersion") long expectedVersion,
                   @Param("owner") String owner, @Param("now") LocalDateTime now,
                   @Param("expiresAt") LocalDateTime expiresAt);

    @Update("""
            UPDATE processing_task
               SET state = 'SUCCESS', stage = #{stage}, active_fingerprint = NULL,
                   lease_owner = NULL, lease_expires_at = NULL, next_retry_at = NULL,
                   error_code = NULL, error_message = NULL, finished_time = #{now},
                   state_version = state_version + 1, updated_time = #{now}
             WHERE id = #{taskId} AND state = 'PROCESSING'
               AND state_version = #{expectedVersion} AND lease_owner = #{owner}
            """)
    int markSuccess(@Param("taskId") Long taskId, @Param("expectedVersion") long expectedVersion,
                    @Param("owner") String owner, @Param("stage") String stage,
                    @Param("now") LocalDateTime now);

    @Update("""
            UPDATE processing_task
               SET state = 'RETRY_WAIT', stage = #{stage}, lease_owner = NULL,
                   lease_expires_at = NULL, next_retry_at = #{nextRetryAt},
                   error_code = #{errorCode}, error_message = #{errorMessage},
                   state_version = state_version + 1, updated_time = #{now}
             WHERE id = #{taskId} AND state = 'PROCESSING'
               AND state_version = #{expectedVersion} AND lease_owner = #{owner}
               AND attempt_count < max_attempts
            """)
    int markRetryWait(@Param("taskId") Long taskId, @Param("expectedVersion") long expectedVersion,
                      @Param("owner") String owner, @Param("stage") String stage,
                      @Param("nextRetryAt") LocalDateTime nextRetryAt,
                      @Param("errorCode") String errorCode, @Param("errorMessage") String errorMessage,
                      @Param("now") LocalDateTime now);

    @Update("""
            UPDATE processing_task
               SET state = #{terminalState}, stage = #{stage}, active_fingerprint = NULL,
                   lease_owner = NULL, lease_expires_at = NULL, next_retry_at = NULL,
                   error_code = #{errorCode}, error_message = #{errorMessage}, finished_time = #{now},
                   state_version = state_version + 1, updated_time = #{now}
             WHERE id = #{taskId} AND state_version = #{expectedVersion}
               AND state NOT IN ('SUCCESS', 'FAILED', 'DEAD')
               AND (#{owner} IS NULL OR lease_owner = #{owner})
            """)
    int markTerminal(@Param("taskId") Long taskId, @Param("expectedVersion") long expectedVersion,
                     @Param("owner") String owner, @Param("terminalState") String terminalState,
                     @Param("stage") String stage, @Param("errorCode") String errorCode,
                     @Param("errorMessage") String errorMessage, @Param("now") LocalDateTime now);
}
