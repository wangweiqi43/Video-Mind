ALTER TABLE video_file
  ADD COLUMN active_file_md5 CHAR(32)
    GENERATED ALWAYS AS (CASE WHEN deleted = 0 THEN file_md5 ELSE NULL END) STORED,
  ADD CONSTRAINT uk_video_file_user_md5 UNIQUE (user_id, active_file_md5);
