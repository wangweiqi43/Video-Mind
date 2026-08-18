package com.videomind.module.video.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.videomind.module.video.dto.VideoUploadResponse;
import com.videomind.module.video.entity.VideoFile;
import java.util.List;
import org.springframework.web.multipart.MultipartFile;

public interface VideoFileService extends IService<VideoFile> {

    VideoUploadResponse upload(MultipartFile file, Long userId);

    VideoFile getUploadedByMd5(String fileMd5, Long userId);

    VideoFile getReusableUploadedByMd5(String fileMd5, Long userId);

    SaveUploadedVideoResult saveUploadedOrReuse(VideoFile candidate);

    VideoUploadResponse toUploadResponse(VideoFile videoFile, String message, boolean duplicate);

    void deleteVideo(Long videoId, Long userId);

    List<VideoFile> listVideos(Long userId);

    VideoFile getVideoDetail(Long videoId, Long userId);

    record SaveUploadedVideoResult(VideoFile video, boolean reused) {
    }
}
