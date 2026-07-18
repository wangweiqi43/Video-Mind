ALTER TABLE video_agent_task ADD COLUMN report_id VARCHAR(128) NULL;
CREATE INDEX idx_video_research_version ON video_agent_task(video_id,user_id,task_type,version,created_at);
