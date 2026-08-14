package com.videomind.module.task.analysis.tencent;

public interface TencentAsrApiTransport {
    String post(String action, String payload);
}
