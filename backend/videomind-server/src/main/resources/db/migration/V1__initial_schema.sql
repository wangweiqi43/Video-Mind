CREATE TABLE video_file (
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
  created_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted TINYINT NOT NULL DEFAULT 0,
  KEY idx_user_created_time (user_id, created_time),
  KEY idx_file_md5 (file_md5)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE video_upload_session (
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

CREATE TABLE task_record (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  video_id BIGINT NOT NULL,
  video_md5 CHAR(32) NOT NULL,
  task_status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
  auto_vectorize TINYINT NOT NULL DEFAULT 0,
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

CREATE TABLE video_transcription (
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

CREATE TABLE ai_summary_result (
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

CREATE TABLE chat_session (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  title VARCHAR(255) NOT NULL DEFAULT '新会话',
  memory_summary LONGTEXT NULL,
  created_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted TINYINT NOT NULL DEFAULT 0,
  KEY idx_user_updated_time (user_id, updated_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE chat_message (
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
