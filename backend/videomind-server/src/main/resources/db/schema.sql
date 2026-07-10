CREATE DATABASE IF NOT EXISTS videomind DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE videomind;

CREATE TABLE IF NOT EXISTS video_file (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  original_filename VARCHAR(255) NOT NULL,
  file_md5 CHAR(32) NOT NULL,
  file_size BIGINT NOT NULL,
  content_type VARCHAR(128),
  minio_bucket VARCHAR(128),
  minio_object_key VARCHAR(512),
  upload_status VARCHAR(32) NOT NULL DEFAULT 'UPLOADED',
  duration_seconds INT,
  agent_knowledge_base_id VARCHAR(128) NULL,
  transcript_version INT NOT NULL DEFAULT 0,
  agent_ingest_status VARCHAR(32) NULL,
  summary_status VARCHAR(32) NULL,
  summary_version INT NOT NULL DEFAULT 0,
  latest_summary_id VARCHAR(128) NULL,
  latest_presentation_id VARCHAR(128) NULL,
  agent_last_error VARCHAR(1024) NULL,
  agent_updated_at DATETIME NULL,
  created_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted TINYINT NOT NULL DEFAULT 0,
  KEY idx_user_created_time (user_id, created_time),
  KEY idx_file_md5 (file_md5)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS video_upload_session (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  upload_id VARCHAR(64) NOT NULL,
  user_id BIGINT NOT NULL,
  original_filename VARCHAR(255) NOT NULL,
  file_md5 CHAR(32) NOT NULL,
  file_size BIGINT NOT NULL,
  content_type VARCHAR(128),
  total_parts INT NOT NULL,
  chunk_size BIGINT NOT NULL,
  uploaded_parts INT NOT NULL DEFAULT 0,
  upload_status VARCHAR(32) NOT NULL DEFAULT 'UPLOADING',
  video_id BIGINT NULL,
  created_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_upload_id (upload_id),
  KEY idx_user_created_time (user_id, created_time),
  KEY idx_file_md5 (file_md5)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS task_record (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  video_id BIGINT NOT NULL,
  video_md5 CHAR(32) NOT NULL,
  task_status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
  auto_vectorize TINYINT NOT NULL DEFAULT 0,
  retry_count INT NOT NULL DEFAULT 0,
  error_message VARCHAR(1024),
  started_time DATETIME NULL,
  finished_time DATETIME NULL,
  created_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted TINYINT NOT NULL DEFAULT 0,
  KEY idx_user_created_time (user_id, created_time),
  KEY idx_video_id (video_id),
  KEY idx_video_md5_status (video_md5, task_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS video_transcription (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  task_id BIGINT NOT NULL,
  video_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  language VARCHAR(32),
  transcription_text LONGTEXT NOT NULL,
  created_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_task_id (task_id),
  KEY idx_video_id (video_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS ai_summary_result (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  task_id BIGINT NOT NULL,
  video_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  summary_text LONGTEXT NOT NULL,
  summary_json JSON NULL,
  model_name VARCHAR(128),
  created_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_task_id (task_id),
  KEY idx_video_id (video_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS chat_session (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  video_id BIGINT NOT NULL,
  title VARCHAR(255) NOT NULL DEFAULT '新会话',
  memory_summary LONGTEXT NULL,
  created_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted TINYINT NOT NULL DEFAULT 0,
  KEY idx_user_updated_time (user_id, updated_time),
  KEY idx_video_updated_time (video_id, updated_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS chat_message (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  session_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  role VARCHAR(32) NOT NULL,
  content LONGTEXT NOT NULL,
  references_json JSON NULL,
  created_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  deleted TINYINT NOT NULL DEFAULT 0,
  KEY idx_session_created_time (session_id, created_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS conversation_summary (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  conversation_id BIGINT NOT NULL,
  summary_text MEDIUMTEXT NOT NULL,
  covered_start_message_id BIGINT NOT NULL,
  covered_end_message_id BIGINT NOT NULL,
  covered_turn_count INT NOT NULL DEFAULT 0,
  summary_version INT NOT NULL DEFAULT 1,
  model_name VARCHAR(100) NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'active',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY idx_conversation_id (conversation_id),
  KEY idx_conversation_status (conversation_id, status),
  KEY idx_covered_end_message_id (covered_end_message_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

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
