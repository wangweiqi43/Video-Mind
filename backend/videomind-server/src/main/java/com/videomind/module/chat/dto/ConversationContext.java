package com.videomind.module.chat.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConversationContext {

    @JsonProperty("conversation_id")
    private Long conversationId;
    private SummarySnapshot summary;
    @JsonProperty("recent_turns")
    private List<ConversationTurn> recentTurns;
    @JsonProperty("updated_at")
    private String updatedAt;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SummarySnapshot {

        @JsonProperty("summary_id")
        private Long summaryId;
        @JsonProperty("summary_text")
        private String summaryText;
        @JsonProperty("covered_start_message_id")
        private Long coveredStartMessageId;
        @JsonProperty("covered_end_message_id")
        private Long coveredEndMessageId;
        @JsonProperty("covered_turn_count")
        private Integer coveredTurnCount;
    }
}
