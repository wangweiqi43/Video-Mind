ALTER TABLE task_record
  ADD COLUMN retry_count INT NOT NULL DEFAULT 0 AFTER auto_vectorize;
