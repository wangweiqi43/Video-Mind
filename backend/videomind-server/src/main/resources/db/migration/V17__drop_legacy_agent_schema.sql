DROP TABLE IF EXISTS mindagent_binding;
DROP TABLE IF EXISTS video_agent_task;

ALTER TABLE chat_session
  DROP INDEX idx_chat_session_user_video_mode_updated,
  DROP COLUMN application_mode,
  DROP COLUMN mindagent_conversation_id;

ALTER TABLE video_file
  DROP COLUMN agent_source_knowledge_base_id,
  DROP COLUMN agent_report_knowledge_base_id,
  DROP COLUMN agent_ingest_version,
  DROP COLUMN agent_ingest_status,
  DROP COLUMN agent_report_status,
  DROP COLUMN agent_report_version,
  DROP COLUMN agent_report_profile,
  DROP COLUMN latest_presentation_id,
  DROP COLUMN agent_last_error,
  DROP COLUMN agent_updated_at;
