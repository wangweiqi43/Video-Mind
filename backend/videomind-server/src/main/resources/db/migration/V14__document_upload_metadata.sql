ALTER TABLE document_version
  ADD COLUMN original_file_size BIGINT NOT NULL DEFAULT 0 AFTER original_object_key,
  ADD COLUMN original_content_type VARCHAR(255) NOT NULL DEFAULT 'application/octet-stream' AFTER original_file_size;
