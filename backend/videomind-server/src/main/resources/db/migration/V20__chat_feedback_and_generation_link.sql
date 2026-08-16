ALTER TABLE chat_message
  ADD COLUMN generation_id BIGINT NULL AFTER user_id,
  ADD KEY idx_chat_message_generation (generation_id);

CREATE TABLE chat_message_feedback (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  message_id BIGINT NOT NULL,
  generation_id BIGINT NOT NULL,
  session_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  rating VARCHAR(16) NOT NULL,
  reason_codes_json JSON NULL,
  detail VARCHAR(500) NULL,
  created_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_chat_feedback_user_message (user_id, message_id),
  KEY idx_chat_feedback_generation (generation_id),
  KEY idx_chat_feedback_session (session_id, created_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
