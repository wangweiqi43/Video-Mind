package com.videomind.module.video.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.videomind.module.video.entity.VideoFile;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;

@Mapper
public interface VideoFileMapper extends BaseMapper<VideoFile> {

    @Insert("""
            INSERT IGNORE INTO video_file
                (user_id, original_filename, file_md5, file_size, content_type,
                 minio_bucket, minio_object_key, upload_status, created_time, updated_time, deleted)
            VALUES
                (#{userId}, #{originalFilename}, #{fileMd5}, #{fileSize}, #{contentType},
                 #{minioBucket}, #{minioObjectKey}, #{uploadStatus}, #{createdTime}, #{updatedTime}, 0)
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertIgnoreUserMd5(VideoFile videoFile);
}

