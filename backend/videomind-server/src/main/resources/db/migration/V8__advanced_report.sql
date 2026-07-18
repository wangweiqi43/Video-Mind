SET @report_id_exists = (
  SELECT COUNT(*)
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'video_agent_task'
    AND COLUMN_NAME = 'report_id'
);
SET @report_id_ddl = IF(
  @report_id_exists = 0,
  'ALTER TABLE video_agent_task ADD COLUMN report_id VARCHAR(128) NULL',
  'SELECT 1'
);
PREPARE videomind_statement FROM @report_id_ddl;
EXECUTE videomind_statement;
DEALLOCATE PREPARE videomind_statement;

SET @research_index_exists = (
  SELECT COUNT(*)
  FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'video_agent_task'
    AND INDEX_NAME = 'idx_video_research_version'
);
SET @research_index_ddl = IF(
  @research_index_exists = 0,
  'CREATE INDEX idx_video_research_version ON video_agent_task(video_id,user_id,task_type,version,created_at)',
  'SELECT 1'
);
PREPARE videomind_statement FROM @research_index_ddl;
EXECUTE videomind_statement;
DEALLOCATE PREPARE videomind_statement;
