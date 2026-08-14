ALTER TABLE chat_session
  ADD COLUMN last_message_preview VARCHAR(512) NULL AFTER mindagent_conversation_id,
  ADD KEY idx_chat_session_user_video_mode_updated
    (user_id, video_id, application_mode, updated_time);
