package com.videomind.infrastructure.storage;

import com.videomind.infrastructure.storage.dto.StoredObject;
import java.io.InputStream;
import java.time.Duration;

public interface ObjectStorageService {

    StoredObject putObject(String objectKey, InputStream inputStream, long size, String contentType);

    InputStream getObject(String bucket, String objectKey);

    boolean objectExists(String bucket, String objectKey);

    void removeObject(String bucket, String objectKey);

    String presignGetUrl(String bucket, String objectKey, Duration expiry);
}
