package com.videomind.module.knowledge.timeline;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;

public interface VideoAsrSegmentMapper extends BaseMapper<VideoAsrSegment> {
    @Insert("""
            INSERT INTO video_asr_segment
                (task_id, video_id, user_id, segment_index, start_ms, end_ms, text, confidence, created_time)
            VALUES
                (#{taskId}, #{videoId}, #{userId}, #{segmentIndex}, #{startMs}, #{endMs},
                 #{text}, #{confidence}, #{createdTime})
            ON DUPLICATE KEY UPDATE
                video_id = VALUES(video_id), user_id = VALUES(user_id),
                start_ms = VALUES(start_ms), end_ms = VALUES(end_ms),
                text = VALUES(text), confidence = VALUES(confidence)
            """)
    int upsert(VideoAsrSegment segment);
}
