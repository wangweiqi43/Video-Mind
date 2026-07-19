package com.videomind.module.agent.controller;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

class AdvancedReportControllerMappingTest {
    @Test
    void exposesEnsureAtThePathUsedByTheFrontend() throws Exception {
        RequestMapping base = AdvancedReportController.class.getAnnotation(RequestMapping.class);
        PostMapping ensure = AdvancedReportController.class.getDeclaredMethod("ensure", Long.class)
                .getAnnotation(PostMapping.class);
        GetMapping detail = AdvancedReportController.class.getDeclaredMethod("detail", Long.class)
                .getAnnotation(GetMapping.class);

        assertThat(base.value()).containsExactly("/api/videos/{videoId}");
        assertThat(ensure.value()).containsExactly("/advanced-report:ensure");
        assertThat(detail.value()).containsExactly("/advanced-report");
    }
}
