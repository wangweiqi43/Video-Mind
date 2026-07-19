package com.videomind.infrastructure.storage.impl;

import com.videomind.common.exception.BizException;
import com.videomind.config.MinioProperties;
import com.videomind.infrastructure.storage.ObjectStorageService;
import com.videomind.infrastructure.storage.dto.StoredObject;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import java.io.InputStream;
import java.time.Duration;
import io.minio.http.Method;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class MinioObjectStorageService implements ObjectStorageService {

    private static final String DEFAULT_CONTENT_TYPE = "application/octet-stream";

    private final MinioClient minioClient;
    private final MinioClient presignClient;
    private final MinioProperties minioProperties;

    public MinioObjectStorageService(MinioClient minioClient, MinioProperties minioProperties) {
        this.minioClient = minioClient;
        this.minioProperties = minioProperties;
        String presignEndpoint = StringUtils.hasText(minioProperties.getPresignEndpoint())
                ? minioProperties.getPresignEndpoint()
                : minioProperties.getEndpoint();
        this.presignClient = MinioClient.builder()
                .endpoint(presignEndpoint)
                .credentials(minioProperties.getAccessKey(), minioProperties.getSecretKey())
                // MinIO defaults to us-east-1. Pinning it keeps pre-signing local and avoids
                // probing the Docker-only public endpoint from the Windows host.
                .region("us-east-1")
                .build();
    }

    @Override
    public StoredObject putObject(String objectKey, InputStream inputStream, long size, String contentType) {
        try {
            ensureBucketExists();
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(minioProperties.getBucket())
                    .object(objectKey)
                    .stream(inputStream, size, -1)
                    .contentType(StringUtils.hasText(contentType) ? contentType : DEFAULT_CONTENT_TYPE)
                    .build());
            return StoredObject.builder()
                    .bucket(minioProperties.getBucket())
                    .objectKey(objectKey)
                    .build();
        } catch (Exception ex) {
            throw new BizException(500, "上传视频到 MinIO 失败：" + ex.getMessage());
        }
    }

    @Override
    public InputStream getObject(String bucket, String objectKey) {
        try {
            return minioClient.getObject(GetObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .build());
        } catch (Exception ex) {
            throw new BizException(500, "从 MinIO 读取视频失败：" + ex.getMessage());
        }
    }

    @Override
    public boolean objectExists(String bucket, String objectKey) {
        if (!StringUtils.hasText(bucket) || !StringUtils.hasText(objectKey)) {
            return false;
        }
        try {
            minioClient.statObject(StatObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .build());
            return true;
        } catch (Exception ex) {
            return false;
        }
    }

    @Override
    public void removeObject(String bucket, String objectKey) {
        if (!StringUtils.hasText(bucket) || !StringUtils.hasText(objectKey)) {
            return;
        }
        try {
            minioClient.removeObject(RemoveObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .build());
        } catch (Exception ex) {
            throw new BizException(500, "删除 MinIO 视频失败：" + ex.getMessage());
        }
    }

    @Override
    public String presignGetUrl(String bucket, String objectKey, Duration expiry) {
        if (!StringUtils.hasText(bucket) || !StringUtils.hasText(objectKey)) {
            throw new BizException(400, "无法为缺失的 MinIO 对象生成访问地址");
        }
        try {
            int seconds = Math.toIntExact(Math.max(1, Math.min(expiry.toSeconds(), 604800)));
            return presignClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.GET)
                    .bucket(bucket)
                    .object(objectKey)
                    .expiry(seconds)
                    .build());
        } catch (Exception ex) {
            throw new BizException(500, "生成 MinIO 短期访问地址失败，请稍后重试");
        }
    }

    private void ensureBucketExists() throws Exception {
        boolean exists = minioClient.bucketExists(BucketExistsArgs.builder()
                .bucket(minioProperties.getBucket())
                .build());
        if (!exists) {
            minioClient.makeBucket(MakeBucketArgs.builder()
                    .bucket(minioProperties.getBucket())
                    .build());
        }
    }
}
