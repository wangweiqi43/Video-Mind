package com.videomind.infrastructure.storage.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class StoredObject {

    private String bucket;
    private String objectKey;
}

