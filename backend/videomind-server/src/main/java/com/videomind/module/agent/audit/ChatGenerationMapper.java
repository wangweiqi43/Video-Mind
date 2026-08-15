package com.videomind.module.agent.audit;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

public interface ChatGenerationMapper extends BaseMapper<ChatGeneration> {

    @Update("""
            UPDATE chat_generation
               SET status = 'CANCEL_REQUESTED', updated_time = #{now}
             WHERE id = #{generationId}
               AND user_id = #{userId}
               AND status = 'RUNNING'
            """)
    int requestCancellation(@Param("generationId") Long generationId,
                            @Param("userId") Long userId,
                            @Param("now") LocalDateTime now);

    @Update("""
            UPDATE chat_generation
               SET status = 'CANCELLED', partial_answer = #{partialAnswer},
                   error_code = NULL, error_message = NULL,
                   finished_time = #{now}, updated_time = #{now}
             WHERE id = #{generationId}
               AND status IN ('RUNNING', 'CANCEL_REQUESTED')
            """)
    int markCancelled(@Param("generationId") Long generationId,
                      @Param("partialAnswer") String partialAnswer,
                      @Param("now") LocalDateTime now);

    @Update("""
            UPDATE chat_generation
               SET status = 'SUCCESS', partial_answer = #{answer},
                   finished_time = #{now}, updated_time = #{now}
             WHERE id = #{generationId}
               AND status = 'RUNNING'
            """)
    int markSuccess(@Param("generationId") Long generationId,
                    @Param("answer") String answer,
                    @Param("now") LocalDateTime now);

    @Update("""
            UPDATE chat_generation
               SET status = 'FAILED', error_code = #{errorCode}, error_message = #{errorMessage},
                   finished_time = #{now}, updated_time = #{now}
             WHERE id = #{generationId}
               AND status = 'RUNNING'
            """)
    int markFailed(@Param("generationId") Long generationId,
                   @Param("errorCode") String errorCode,
                   @Param("errorMessage") String errorMessage,
                   @Param("now") LocalDateTime now);
}
