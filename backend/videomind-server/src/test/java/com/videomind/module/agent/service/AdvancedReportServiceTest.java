package com.videomind.module.agent.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AdvancedReportServiceTest {

    @Test
    void targetLengthFollowsVideoDurationBands() {
        assertThat(AdvancedReportService.targetLength(null)).isEqualTo(1000);
        assertThat(AdvancedReportService.targetLength(600)).isEqualTo(1000);
        assertThat(AdvancedReportService.targetLength(601)).isEqualTo(1200);
        assertThat(AdvancedReportService.targetLength(1801)).isEqualTo(1500);
        assertThat(AdvancedReportService.targetLength(3601)).isEqualTo(1800);
        assertThat(AdvancedReportService.targetLength(7201)).isEqualTo(2000);
    }

    @Test
    void stripsReferenceSectionsFromVideoMindSummary() {
        assertThat(AdvancedReportService.stripReferenceSection(
                "# 高级摘要总结\n\n正文\n\n## 转录原文引用\n\n[1] 第一段"))
                .isEqualTo("# 高级摘要总结\n\n正文");
        assertThat(AdvancedReportService.stripReferenceSection(
                "# Notes\r\n\r\nBody\r\n\r\n### References:\r\n- https://example.com"))
                .isEqualTo("# Notes\r\n\r\nBody");
        assertThat(AdvancedReportService.stripReferenceSection(
                "正文提到了参考来源，但它不是标题。"))
                .isEqualTo("正文提到了参考来源，但它不是标题。");
    }

    @Test
    void leavesReportsWithoutReferenceHeadingUntouched() {
        String markdown = "# 高级摘要总结\n\n完整正文";
        assertThat(AdvancedReportService.stripReferenceSection(markdown)).isEqualTo(markdown);
    }
}
