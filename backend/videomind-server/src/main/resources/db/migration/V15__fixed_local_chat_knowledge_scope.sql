ALTER TABLE chat_session
  ADD COLUMN knowledge_base_ids_json JSON NULL AFTER last_message_preview;

UPDATE chat_session
SET application_mode = 'LOCAL'
WHERE application_mode IS NULL OR application_mode <> 'LOCAL';
