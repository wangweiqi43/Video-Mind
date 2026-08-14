package com.videomind.module.task.analysis.ocr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class LocalOcrResponseParserTest {
    private final LocalOcrResponseParser parser = new LocalOcrResponseParser(new ObjectMapper());

    @Test
    void parsesSimpleLocalOcrContract() {
        var result = parser.parse("{\"result\":[{\"text\":\"第一行\",\"confidence\":0.9},"
                + "{\"text\":\"第二行\",\"confidence\":0.7}]}");
        assertThat(result.text()).isEqualTo("第一行\n第二行");
        assertThat(result.confidence()).isEqualTo(0.8);
    }

    @Test
    void parsesPaddleRecTextsAndScores() {
        var result = parser.parse("""
                {"result":[{"prunedResult":{"rec_texts":["标题","正文"],
                "rec_scores":[0.96,0.84]}}]}
                """);
        assertThat(result.text()).isEqualTo("标题\n正文");
        assertThat(result.confidence()).isCloseTo(0.9, within(0.000_001));
    }
}
