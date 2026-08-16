package com.videomind.module.knowledge.vision;

public interface VisionClient {
    VisionResult describe(byte[] image, String mediaType);

    record VisionResult(boolean success, String description, String model, String errorCode) {
        public static VisionResult success(String description, String model) {
            return new VisionResult(true, description, model, null);
        }

        public static VisionResult degraded(String model, String errorCode) {
            return new VisionResult(false, null, model, errorCode);
        }
    }
}
