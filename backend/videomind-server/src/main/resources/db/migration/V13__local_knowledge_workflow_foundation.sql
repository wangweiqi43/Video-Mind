CREATE TABLE knowledge_base (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  type VARCHAR(16) NOT NULL,
  video_id BIGINT NULL,
  name VARCHAR(255) NOT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'EMPTY',
  active TINYINT(1) NOT NULL DEFAULT 1,
  created_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_knowledge_base_video (video_id),
  KEY idx_knowledge_base_user_type (user_id, type, active)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE knowledge_document (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  knowledge_base_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  source_type VARCHAR(32) NOT NULL,
  title VARCHAR(255) NOT NULL,
  sha256 CHAR(64) NOT NULL,
  dedupe_key CHAR(64) NULL,
  current_version_id BIGINT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'UPLOADING',
  failure_code VARCHAR(128) NULL,
  failure_message VARCHAR(1024) NULL,
  active TINYINT(1) NOT NULL DEFAULT 1,
  created_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_document_kb_dedupe (knowledge_base_id, dedupe_key),
  KEY idx_document_kb_status (knowledge_base_id, status, active),
  KEY idx_document_user_created (user_id, created_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE document_version (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  document_id BIGINT NOT NULL,
  version_number INT NOT NULL,
  original_bucket VARCHAR(128) NOT NULL,
  original_object_key VARCHAR(768) NOT NULL,
  markdown_bucket VARCHAR(128) NULL,
  markdown_object_key VARCHAR(768) NULL,
  parser VARCHAR(64) NULL,
  mineru_task_id VARCHAR(128) NULL,
  processing_stage VARCHAR(64) NULL,
  index_status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
  embedding_model VARCHAR(128) NULL,
  embedding_dimension INT NULL,
  chunk_count INT NOT NULL DEFAULT 0,
  created_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_document_version_number (document_id, version_number),
  KEY idx_document_version_status (document_id, index_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE document_asset (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  document_version_id BIGINT NOT NULL,
  ordinal_no INT NOT NULL,
  asset_type VARCHAR(32) NOT NULL,
  media_type VARCHAR(128) NOT NULL,
  bucket VARCHAR(128) NOT NULL,
  object_key VARCHAR(768) NOT NULL,
  source_path VARCHAR(768) NULL,
  created_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_document_asset_ordinal (document_version_id, ordinal_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE document_chunk (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  embedding_id VARCHAR(64) NOT NULL,
  user_id BIGINT NOT NULL,
  knowledge_base_id BIGINT NOT NULL,
  document_id BIGINT NOT NULL,
  document_version_id BIGINT NOT NULL,
  source_type VARCHAR(32) NOT NULL,
  chunk_index INT NOT NULL,
  parent_index INT NOT NULL,
  child_index INT NOT NULL,
  heading VARCHAR(512) NULL,
  content MEDIUMTEXT NOT NULL,
  parent_content MEDIUMTEXT NOT NULL,
  start_offset INT NULL,
  end_offset INT NULL,
  start_ms BIGINT NULL,
  end_ms BIGINT NULL,
  published TINYINT(1) NOT NULL DEFAULT 0,
  created_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_document_chunk_embedding (embedding_id),
  UNIQUE KEY uk_document_chunk_order (document_version_id, chunk_index),
  KEY idx_document_chunk_scope (user_id, knowledge_base_id, published)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE processing_task (
  id BIGINT PRIMARY KEY,
  event_id VARCHAR(128) NOT NULL,
  user_id BIGINT NOT NULL,
  task_type VARCHAR(32) NOT NULL,
  business_id BIGINT NOT NULL,
  business_fingerprint VARCHAR(160) NOT NULL,
  active_fingerprint VARCHAR(160) NULL,
  state VARCHAR(32) NOT NULL DEFAULT 'PENDING',
  stage VARCHAR(64) NOT NULL,
  state_version BIGINT NOT NULL DEFAULT 0,
  lease_owner VARCHAR(128) NULL,
  lease_expires_at DATETIME NULL,
  attempt_count INT NOT NULL DEFAULT 0,
  max_attempts INT NOT NULL DEFAULT 5,
  next_retry_at DATETIME NULL,
  replay_generation INT NOT NULL DEFAULT 0,
  error_code VARCHAR(128) NULL,
  error_message VARCHAR(2048) NULL,
  started_time DATETIME NULL,
  finished_time DATETIME NULL,
  created_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_processing_task_event (event_id),
  UNIQUE KEY uk_processing_task_active_fingerprint (active_fingerprint),
  KEY idx_processing_task_business (task_type, business_id, created_time),
  KEY idx_processing_task_retry (state, next_retry_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE task_checkpoint (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  task_id BIGINT NOT NULL,
  stage VARCHAR(64) NOT NULL,
  status VARCHAR(32) NOT NULL,
  artifact_json JSON NULL,
  checksum VARCHAR(128) NULL,
  completed_time DATETIME NULL,
  created_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_task_checkpoint_stage (task_id, stage)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE mq_transaction_event (
  event_id VARCHAR(128) PRIMARY KEY,
  task_id BIGINT NOT NULL,
  topic VARCHAR(255) NOT NULL,
  tag VARCHAR(128) NULL,
  transaction_state VARCHAR(32) NOT NULL,
  payload_json JSON NOT NULL,
  created_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY idx_mq_transaction_task (task_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE mq_consume_record (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  consumer_group VARCHAR(255) NOT NULL,
  event_id VARCHAR(128) NOT NULL,
  task_id BIGINT NOT NULL,
  consume_status VARCHAR(32) NOT NULL,
  consumed_time DATETIME NULL,
  created_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_mq_consume_group_event (consumer_group, event_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE video_asr_segment (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  task_id BIGINT NOT NULL,
  video_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  segment_index INT NOT NULL,
  start_ms BIGINT NOT NULL,
  end_ms BIGINT NOT NULL,
  text MEDIUMTEXT NOT NULL,
  confidence DECIMAL(8,6) NULL,
  created_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_video_asr_segment (task_id, segment_index),
  KEY idx_video_asr_time (video_id, start_ms)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE video_ocr_observation (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  task_id BIGINT NOT NULL,
  video_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  observation_index INT NOT NULL,
  start_ms BIGINT NOT NULL,
  end_ms BIGINT NOT NULL,
  text MEDIUMTEXT NOT NULL,
  confidence DECIMAL(8,6) NULL,
  frame_object_key VARCHAR(768) NULL,
  boxes_json JSON NULL,
  created_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_video_ocr_observation (task_id, observation_index),
  KEY idx_video_ocr_time (video_id, start_ms)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE video_timeline (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  task_id BIGINT NOT NULL,
  video_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  version_number INT NOT NULL,
  bucket VARCHAR(128) NOT NULL,
  markdown_object_key VARCHAR(768) NOT NULL,
  event_json_object_key VARCHAR(768) NOT NULL,
  status VARCHAR(32) NOT NULL,
  created_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_video_timeline_version (video_id, version_number)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE video_report (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  task_id BIGINT NOT NULL,
  video_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  version_number INT NOT NULL,
  bucket VARCHAR(128) NOT NULL,
  markdown_object_key VARCHAR(768) NOT NULL,
  json_object_key VARCHAR(768) NOT NULL,
  status VARCHAR(32) NOT NULL,
  model_name VARCHAR(128) NULL,
  created_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_video_report_version (video_id, version_number)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE conversation_knowledge_base (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  conversation_id BIGINT NOT NULL,
  knowledge_base_id BIGINT NOT NULL,
  created_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_conversation_knowledge_base (conversation_id, knowledge_base_id),
  KEY idx_conversation_kb_scope (knowledge_base_id, conversation_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE chat_generation (
  id BIGINT PRIMARY KEY,
  conversation_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  client_request_id VARCHAR(128) NOT NULL,
  status VARCHAR(32) NOT NULL,
  question MEDIUMTEXT NOT NULL,
  partial_answer MEDIUMTEXT NULL,
  error_code VARCHAR(128) NULL,
  error_message VARCHAR(2048) NULL,
  started_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  finished_time DATETIME NULL,
  created_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_chat_generation_request (user_id, client_request_id),
  KEY idx_chat_generation_conversation (conversation_id, status, created_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE agent_execution (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  generation_id BIGINT NOT NULL,
  profile VARCHAR(32) NOT NULL,
  state VARCHAR(32) NOT NULL,
  route VARCHAR(64) NULL,
  tool_calls INT NOT NULL DEFAULT 0,
  replan_count INT NOT NULL DEFAULT 0,
  token_usage BIGINT NOT NULL DEFAULT 0,
  deadline_at DATETIME NULL,
  created_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_agent_execution_generation (generation_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE agent_step (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  execution_id BIGINT NOT NULL,
  step_index INT NOT NULL,
  node_type VARCHAR(32) NOT NULL,
  status VARCHAR(32) NOT NULL,
  input_json JSON NULL,
  output_json JSON NULL,
  started_time DATETIME NULL,
  finished_time DATETIME NULL,
  created_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_agent_step_order (execution_id, step_index)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
