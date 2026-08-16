ALTER TABLE document_version
    ADD COLUMN raw_markdown_bucket VARCHAR(128) NULL AFTER original_content_type,
    ADD COLUMN raw_markdown_object_key VARCHAR(1024) NULL AFTER raw_markdown_bucket,
    ADD COLUMN manifest_bucket VARCHAR(128) NULL AFTER markdown_object_key,
    ADD COLUMN manifest_object_key VARCHAR(1024) NULL AFTER manifest_bucket,
    ADD COLUMN visual_status VARCHAR(32) NOT NULL DEFAULT 'NOT_APPLICABLE' AFTER processing_stage,
    ADD COLUMN image_count INT NOT NULL DEFAULT 0 AFTER visual_status,
    ADD COLUMN described_image_count INT NOT NULL DEFAULT 0 AFTER image_count;

ALTER TABLE document_asset
    ADD COLUMN content_hash CHAR(64) NULL AFTER source_path,
    ADD COLUMN description MEDIUMTEXT NULL AFTER content_hash,
    ADD COLUMN vision_status VARCHAR(32) NOT NULL DEFAULT 'PENDING' AFTER description,
    ADD COLUMN vision_model VARCHAR(128) NULL AFTER vision_status,
    ADD COLUMN vision_error_code VARCHAR(128) NULL AFTER vision_model,
    ADD COLUMN updated_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
        ON UPDATE CURRENT_TIMESTAMP AFTER created_time;

CREATE TABLE document_upload_idempotency (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    knowledge_base_id BIGINT NOT NULL,
    idempotency_key VARCHAR(200) NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    document_id BIGINT NOT NULL,
    document_version_id BIGINT NOT NULL,
    processing_task_id BIGINT NULL,
    created_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_document_upload_idempotency (user_id, knowledge_base_id, idempotency_key),
    KEY idx_document_upload_task (processing_task_id),
    CONSTRAINT fk_document_upload_idem_kb FOREIGN KEY (knowledge_base_id) REFERENCES knowledge_base(id),
    CONSTRAINT fk_document_upload_idem_document FOREIGN KEY (document_id) REFERENCES knowledge_document(id),
    CONSTRAINT fk_document_upload_idem_version FOREIGN KEY (document_version_id) REFERENCES document_version(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
