package com.videomind.module.agent.controller;

import com.videomind.agentclient.AgentClientProperties;
import com.videomind.common.api.ApiResponse;
import com.videomind.module.agent.dto.AgentCapabilitiesResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/system")
public class SystemCapabilitiesController {

    private final AgentClientProperties properties;

    @GetMapping("/capabilities")
    public ApiResponse<AgentCapabilitiesResponse> capabilities() {
        boolean agentEnabled = properties.isEnabled();
        return ApiResponse.success(AgentCapabilitiesResponse.builder()
                .normalChat(true)
                .knowledgeExtended(true)
                .advancedMode(true)
                .advancedChat(agentEnabled && properties.isChatEnabled())
                .webSearch(agentEnabled && properties.isChatEnabled() && properties.isWebSearchEnabled())
                .advancedReport(agentEnabled && properties.isAdvancedReportEnabled())
                .pptGeneration(agentEnabled && properties.isPresentationEnabled())
                .reportExportPdf(false)
                .reportExportDocx(false)
                .reportExportMode("unavailable")
                .suggestedQuestions(false)
                .build());
    }
}
