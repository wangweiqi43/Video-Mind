package com.videomind.module.agent.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class AgentCapabilitiesResponse {

    @JsonProperty("normal_chat")
    private boolean normalChat;
    @JsonProperty("knowledge_extended")
    private boolean knowledgeExtended;
    @JsonProperty("advanced_mode")
    private boolean advancedMode;
    @JsonProperty("agent_enabled")
    private boolean agentEnabled;
    @JsonProperty("advanced_chat")
    private boolean advancedChat;
    @JsonProperty("web_search")
    private boolean webSearch;
    @JsonProperty("advanced_report")
    private boolean advancedReport;
    @JsonProperty("ppt_generation")
    private boolean pptGeneration;
    @JsonProperty("report_export_pdf")
    private boolean reportExportPdf;
    @JsonProperty("report_export_docx")
    private boolean reportExportDocx;
    @JsonProperty("report_export_mode")
    private String reportExportMode;
    @JsonProperty("suggested_questions")
    private boolean suggestedQuestions;
}
