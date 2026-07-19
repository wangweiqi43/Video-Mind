ALTER TABLE video_file
    CHANGE COLUMN agent_knowledge_base_id agent_source_knowledge_base_id VARCHAR(128) NULL,
    ADD COLUMN agent_report_knowledge_base_id VARCHAR(128) NULL AFTER agent_source_knowledge_base_id;
