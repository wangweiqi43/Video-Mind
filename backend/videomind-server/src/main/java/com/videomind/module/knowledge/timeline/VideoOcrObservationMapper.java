package com.videomind.module.knowledge.timeline;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;

public interface VideoOcrObservationMapper extends BaseMapper<VideoOcrObservation> {
    @Insert("""
            INSERT IGNORE INTO video_ocr_observation
                (task_id, video_id, user_id, observation_index, start_ms, end_ms, text,
                 confidence, frame_object_key, boxes_json, created_time)
            VALUES
                (#{taskId}, #{videoId}, #{userId}, #{observationIndex}, #{startMs}, #{endMs}, #{text},
                 #{confidence}, #{frameObjectKey}, #{boxesJson}, #{createdTime})
            """)
    int insertIgnore(VideoOcrObservation observation);
}
