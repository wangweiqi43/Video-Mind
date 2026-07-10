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
