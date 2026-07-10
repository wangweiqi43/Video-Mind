ALTER TABLE video_file ADD COLUMN agent_knowledge_base_id VARCHAR(128) NULL;
ALTER TABLE video_file ADD COLUMN transcript_version INT NOT NULL DEFAULT 0;
ALTER TABLE video_file ADD COLUMN agent_ingest_status VARCHAR(32) NULL;
ALTER TABLE video_file ADD COLUMN summary_status VARCHAR(32) NULL;
ALTER TABLE video_file ADD COLUMN summary_version INT NOT NULL DEFAULT 0;
ALTER TABLE video_file ADD COLUMN latest_summary_id VARCHAR(128) NULL;
ALTER TABLE video_file ADD COLUMN latest_presentation_id VARCHAR(128) NULL;
ALTER TABLE video_file ADD COLUMN agent_last_error VARCHAR(1024) NULL;
ALTER TABLE video_file ADD COLUMN agent_updated_at DATETIME NULL;

CREATE TABLE IF NOT EXISTS video_agent_task (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  video_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  source_task_id BIGINT NULL,
  agent_task_id VARCHAR(128) NOT NULL,
  task_type VARCHAR(32) NOT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
  progress INT NOT NULL DEFAULT 0,
  error_code VARCHAR(128) NULL,
  error_message VARCHAR(1024) NULL,
  artifact_id VARCHAR(128) NULL,
  output_url VARCHAR(2048) NULL,
  version INT NOT NULL DEFAULT 1,
  request_json JSON NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_agent_task_id (agent_task_id),
  KEY idx_video_task_type (video_id, task_type, created_at),
  KEY idx_user_created_at (user_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
