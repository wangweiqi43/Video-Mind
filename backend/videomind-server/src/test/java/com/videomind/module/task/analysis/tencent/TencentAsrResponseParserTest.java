package com.videomind.module.task.analysis.tencent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.videomind.common.exception.BizException;
import org.junit.jupiter.api.Test;

class TencentAsrResponseParserTest {
    private final TencentAsrResponseParser parser = new TencentAsrResponseParser(new ObjectMapper());

    @Test
    void parsesSuccessfulSentenceTimestampsAndPrefersWrittenText() {
        TencentAsrTaskResult result = parser.parse("""
                {
                  "Response": {
                    "RequestId": "request-1",
                    "Data": {
                      "TaskId": 9266418,
                      "Status": 2,
                      "Result": "",
                      "ErrorMsg": "",
                      "ResultDetail": [
                        {"FinalSentence":"欢迎您", "WrittenText":"欢迎您。", "StartMs":20,
                         "EndMs":2380, "SpeakerId":0},
                        {"FinalSentence":"第二句", "StartMs":2500, "EndMs":3200}
                      ]
                    }
                  }
                }
                """);

        assertThat(result.status()).isEqualTo(TencentAsrTaskResult.Status.SUCCEEDED);
        assertThat(result.taskId()).isEqualTo(9_266_418L);
        assertThat(result.text()).isEqualTo("欢迎您。\n第二句");
        assertThat(result.segments()).hasSize(2);
        assertThat(result.segments().get(0).startMs()).isEqualTo(20);
        assertThat(result.segments().get(0).endMs()).isEqualTo(2380);
        assertThat(result.segments().get(0).speakerId()).isZero();
        assertThat(result.requestId()).isEqualTo("request-1");
    }

    @Test
    void mapsWaitingRunningAndFailedStates() {
        assertThat(parseStatus(0).status()).isEqualTo(TencentAsrTaskResult.Status.WAITING);
        assertThat(parseStatus(1).status()).isEqualTo(TencentAsrTaskResult.Status.RUNNING);
        TencentAsrTaskResult failed = parser.parse("""
                {"Response":{"RequestId":"r","Data":{"TaskId":3,"Status":3,
                "Result":"","ErrorMsg":"Failed to download audio file!","ResultDetail":[]}}}
                """);
        assertThat(failed.status()).isEqualTo(TencentAsrTaskResult.Status.FAILED);
        assertThat(failed.errorMessage()).contains("download audio");
    }

    @Test
    void rejectsTencentApiErrorEnvelope() {
        assertThatThrownBy(() -> parser.parse("""
                {"Response":{"Error":{"Code":"AuthFailure.SignatureFailure",
                "Message":"signature mismatch"},"RequestId":"r"}}
                """))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("AuthFailure.SignatureFailure");
    }

    private TencentAsrTaskResult parseStatus(int status) {
        return parser.parse("""
                {"Response":{"RequestId":"r","Data":{"TaskId":1,"Status":%d,
                "Result":"","ErrorMsg":"","ResultDetail":[]}}}
                """.formatted(status));
    }
}
