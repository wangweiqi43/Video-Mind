package com.videomind.module.knowledge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.videomind.module.knowledge.entity.DocumentAsset;
import org.apache.ibatis.annotations.Insert;

public interface DocumentAssetMapper extends BaseMapper<DocumentAsset> {
    @Insert("""
            INSERT IGNORE INTO document_asset
                (document_version_id, ordinal_no, asset_type, media_type, bucket,
                 object_key, source_path, content_hash, description, vision_status,
                 vision_model, vision_error_code, created_time, updated_time)
            VALUES
                (#{documentVersionId}, #{ordinalNo}, #{assetType}, #{mediaType}, #{bucket},
                 #{objectKey}, #{sourcePath}, #{contentHash}, #{description}, #{visionStatus},
                 #{visionModel}, #{visionErrorCode}, #{createdTime}, #{updatedTime})
            """)
    int insertIgnore(DocumentAsset asset);
}
