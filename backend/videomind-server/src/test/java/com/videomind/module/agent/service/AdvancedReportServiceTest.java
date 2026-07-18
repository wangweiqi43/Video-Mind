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
}
