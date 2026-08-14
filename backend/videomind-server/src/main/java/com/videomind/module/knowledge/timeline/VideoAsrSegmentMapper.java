package com.videomind.module.knowledge.timeline;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;

public interface VideoAsrSegmentMapper extends BaseMapper<VideoAsrSegment> {
    @Insert("""
            INSERT IGNORE INTO video_asr_segment
                (task_id, video_id, user_id, segment_index, start_ms, end_ms, text, confidence, created_time)
            VALUES
                (#{taskId}, #{videoId}, #{userId}, #{segmentIndex}, #{startMs}, #{endMs},
                 #{text}, #{confidence}, #{createdTime})
            """)
    int insertIgnore(VideoAsrSegment segment);
}
