ALTER TABLE task_record
  DROP INDEX idx_task_user_video_mode_status,
  DROP CHECK chk_task_record_analysis_mode,
  DROP COLUMN analysis_mode,
  DROP COLUMN auto_vectorize;

CREATE INDEX idx_task_user_video_status_created
  ON task_record(user_id, video_id, task_status, created_time);
