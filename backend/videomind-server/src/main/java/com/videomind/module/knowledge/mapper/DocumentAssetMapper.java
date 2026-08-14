package com.videomind.module.knowledge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.videomind.module.knowledge.entity.DocumentAsset;
import org.apache.ibatis.annotations.Insert;

public interface DocumentAssetMapper extends BaseMapper<DocumentAsset> {
    @Insert("""
            INSERT IGNORE INTO document_asset
                (document_version_id, ordinal_no, asset_type, media_type, bucket,
                 object_key, source_path, created_time)
            VALUES
                (#{documentVersionId}, #{ordinalNo}, #{assetType}, #{mediaType}, #{bucket},
                 #{objectKey}, #{sourcePath}, #{createdTime})
            """)
    int insertIgnore(DocumentAsset asset);
}
