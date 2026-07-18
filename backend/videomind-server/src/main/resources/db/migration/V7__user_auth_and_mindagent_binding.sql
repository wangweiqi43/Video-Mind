CREATE TABLE IF NOT EXISTS app_user (
  id BIGINT PRIMARY KEY AUTO_INCREMENT, public_id VARCHAR(36) NOT NULL, username VARCHAR(64) NOT NULL,
  password_hash VARCHAR(255) NOT NULL, enabled TINYINT(1) NOT NULL DEFAULT 1,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_app_user_public_id(public_id), UNIQUE KEY uk_app_user_username(username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS user_refresh_token (
  id BIGINT PRIMARY KEY AUTO_INCREMENT, user_id BIGINT NOT NULL, token_hash VARCHAR(64) NOT NULL,
  expires_at DATETIME NOT NULL, revoked_at DATETIME NULL, created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_refresh_token_hash(token_hash), KEY idx_refresh_user(user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS mindagent_binding (
  id BIGINT PRIMARY KEY AUTO_INCREMENT, user_id BIGINT NOT NULL, mindagent_subject VARCHAR(64) NOT NULL,
  mindagent_username VARCHAR(64) NULL, access_token_cipher TEXT NOT NULL, refresh_token_cipher TEXT NOT NULL,
  scopes VARCHAR(1024) NOT NULL, access_expires_at DATETIME NOT NULL, status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_binding_user(user_id), UNIQUE KEY uk_binding_subject(mindagent_subject)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
ALTER TABLE chat_session ADD COLUMN application_mode VARCHAR(32) NOT NULL DEFAULT 'NORMAL';
ALTER TABLE chat_session ADD COLUMN mindagent_conversation_id VARCHAR(64) NULL;
