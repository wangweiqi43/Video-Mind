package com.videomind.module.task.analysis.chunk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.videomind.config.TencentAsrProperties;
import org.junit.jupiter.api.Test;

class AudioChunkPlannerTest {

    @Test
    void plansOverlappedLogicalWindowsForLongAudio() {
        AudioChunkPlanner planner = new AudioChunkPlanner(properties(120, 1_000));

        assertThat(planner.plan(250_000)).containsExactly(
                new AudioChunkPlan(0, 0, 121_000, 0, 120_000),
                new AudioChunkPlan(1, 119_000, 241_000, 120_000, 240_000),
                new AudioChunkPlan(2, 239_000, 250_000, 240_000, 250_000));
    }

    @Test
    void keepsShortAudioInOneChunkWithoutArtificialPadding() {
        AudioChunkPlanner planner = new AudioChunkPlanner(properties(120, 1_000));

        assertThat(planner.plan(40_000)).containsExactly(
                new AudioChunkPlan(0, 0, 40_000, 0, 40_000));
    }

    @Test
    void rejectsMissingDurationAndInvalidOverlap() {
        assertThatThrownBy(() -> new AudioChunkPlanner(properties(120, 1_000)).plan(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("AUDIO_DURATION_REQUIRED");
        assertThatThrownBy(() -> new AudioChunkPlanner(properties(1, 1_000)).plan(1_000))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("INVALID_AUDIO_CHUNK_CONFIGURATION");
    }

    private static TencentAsrProperties properties(int seconds, int overlapMillis) {
        TencentAsrProperties properties = new TencentAsrProperties();
        properties.setChunkSeconds(seconds);
        properties.setChunkOverlapMillis(overlapMillis);
        return properties;
    }
}
