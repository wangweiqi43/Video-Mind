ALTER TABLE task_record
  ADD COLUMN analysis_mode VARCHAR(16) NOT NULL DEFAULT 'NORMAL' AFTER auto_vectorize,
  ADD CONSTRAINT chk_task_record_analysis_mode CHECK (analysis_mode IN ('NORMAL', 'ADVANCED')),
  ADD INDEX idx_task_user_video_mode_status (user_id, video_id, analysis_mode, task_status, created_time);

ALTER TABLE video_file
  ADD COLUMN agent_report_status VARCHAR(32) NULL AFTER agent_ingest_status,
  ADD COLUMN agent_report_version INT NOT NULL DEFAULT 0 AFTER agent_report_status,
  ADD COLUMN agent_report_profile VARCHAR(64) NULL AFTER agent_report_version;

ALTER TABLE video_agent_task
  ADD COLUMN profile_version VARCHAR(64) NULL AFTER version;

UPDATE video_file
SET agent_report_status = 'SUCCESS',
    agent_report_version = transcript_version,
    agent_report_profile = 'LIGHT_RESEARCH_V1'
WHERE agent_report_knowledge_base_id IS NOT NULL;
