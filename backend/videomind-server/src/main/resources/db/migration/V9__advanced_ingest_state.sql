ALTER TABLE video_file
    ADD COLUMN agent_ingest_version INT NOT NULL DEFAULT 0 AFTER transcript_version;

ALTER TABLE video_agent_task
    ADD COLUMN stage VARCHAR(64) NULL AFTER status;
