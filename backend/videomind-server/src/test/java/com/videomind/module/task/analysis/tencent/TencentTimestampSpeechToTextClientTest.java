package com.videomind.module.task.analysis.tencent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.videomind.config.TencentAsrProperties;
import com.videomind.infrastructure.storage.ObjectStorageService;
import com.videomind.infrastructure.storage.dto.StoredObject;
import com.videomind.module.task.analysis.dto.AudioExtractionResult;
import com.videomind.module.task.entity.TaskRecord;
import com.videomind.module.video.entity.VideoFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TencentTimestampSpeechToTextClientTest {
    @TempDir
    Path tempDir;

    @Test
    void uploadsPollsAndReturnsTimestampedSegmentsThenCleansTemporaryAudio() throws Exception {
        Path audioFile = tempDir.resolve("audio.wav");
        Files.write(audioFile, new byte[]{1, 2, 3});
        TencentAsrProperties properties = properties();
        ObjectStorageService storage = mock(ObjectStorageService.class);
        when(storage.putObject(eq("asr/task-9/audio.wav"), any(), anyLong(), eq("audio/wav")))
                .thenReturn(StoredObject.builder().bucket("video").objectKey("asr/task-9/audio.wav").build());
        when(storage.presignGetUrl(eq("video"), eq("asr/task-9/audio.wav"), any(Duration.class)))
                .thenReturn("https://minio.example/audio.wav?signature=x");
        TencentAsrApiTransport transport = mock(TencentAsrApiTransport.class);
        when(transport.post(eq("CreateRecTask"), any())).thenReturn(
                "{\"Response\":{\"Data\":{\"TaskId\":1001},\"RequestId\":\"create\"}}");
        when(transport.post(eq("DescribeTaskStatus"), any())).thenReturn(successResponse());
        ObjectMapper mapper = new ObjectMapper();
        var client = new TencentTimestampSpeechToTextClient(properties, storage, transport,
                new TencentAsrResponseParser(mapper), mapper);
        TaskRecord task = new TaskRecord();
        task.setId(9L);

        var result = client.transcribe(AudioExtractionResult.builder().audioPath(audioFile.toString()).build(),
                new VideoFile(), task);

        assertThat(result.getText()).isEqualTo("第一句");
        assertThat(result.getSegments()).hasSize(1);
        assertThat(result.getSegments().get(0).startMs()).isEqualTo(100);
        assertThat(result.getSegments().get(0).endMs()).isEqualTo(900);
        verify(storage).removeObject("video", "asr/task-9/audio.wav");
    }

    private TencentAsrProperties properties() {
        TencentAsrProperties properties = new TencentAsrProperties();
        properties.setSecretId("id");
        properties.setSecretKey("key");
        properties.setPollIntervalMillis(1);
        properties.setTimeoutSeconds(2);
        return properties;
    }

    private String successResponse() {
        return """
                {"Response":{"RequestId":"poll","Data":{"TaskId":1001,"Status":2,
                "Result":"第一句","ErrorMsg":"","ResultDetail":[
                {"FinalSentence":"第一句","StartMs":100,"EndMs":900,"SpeakerId":0}]}}}
                """;
    }
}
