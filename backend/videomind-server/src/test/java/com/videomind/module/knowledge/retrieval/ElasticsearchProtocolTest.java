package com.videomind.module.knowledge.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class ElasticsearchProtocolTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void mappingUsesIkAndInt8HnswWithPublishedProjection() {
        var mapping = ElasticsearchGateway.indexDefinition(mapper, 1024);
        var fields = mapping.path("mappings").path("properties");
        assertThat(fields.path("contentVector").path("dims").asInt()).isEqualTo(1024);
        assertThat(fields.path("contentVector").path("index_options").path("type").asText())
                .isEqualTo("int8_hnsw");
        assertThat(fields.path("content").path("analyzer").asText()).isEqualTo("ik_max_word");
        assertThat(fields.path("content").path("search_analyzer").asText()).isEqualTo("ik_smart");
        assertThat(fields.path("published").path("type").asText()).isEqualTo("boolean");
        assertThat(fields.path("parentContent").path("index").asBoolean()).isFalse();
    }

    @Test
    void keywordAndVectorBodiesHaveIdenticalAuthorizationFilters() {
        var keyword = ElasticsearchGateway.keywordBody(mapper, 7L, java.util.List.of(11L, 12L),
                "部署步骤", 40);
        var vector = ElasticsearchGateway.vectorBody(mapper, 7L, java.util.List.of(11L, 12L),
                new float[] {1, 2}, 40, 200);
        assertThat(keyword.path("size").asInt()).isEqualTo(40);
        assertThat(keyword.toString()).contains("title^3", "heading^2", "published", "knowledgeBaseId");
        assertThat(vector.path("knn").path("k").asInt()).isEqualTo(40);
        assertThat(vector.path("knn").path("num_candidates").asInt()).isEqualTo(200);
        assertThat(vector.toString()).contains("published", "knowledgeBaseId", "query_vector");
    }

    @Test
    void rerankParserRejectsInvalidIndexesAndKeepsTopN() throws Exception {
        var response = mapper.readTree("""
                {"results":[
                  {"index":2,"relevance_score":0.9},
                  {"index":99,"relevance_score":1.0},
                  {"index":0,"relevance_score":0.7}
                ]}
                """);
        assertThat(RealRerankClient.parse(response, 3, 2))
                .containsExactly(new RerankClient.RerankScore(2, 0.9),
                        new RerankClient.RerankScore(0, 0.7));
    }

    @Test
    void retrievalHitKeepsElasticsearchNativeScoreForPreRrfOrdering() throws Exception {
        var response = mapper.readTree("""
                {"hits":{"hits":[{
                  "_id":"chunk-1","_score":7.25,
                  "_source":{"embeddingId":"chunk-1","content":"正文","chunkIndex":1}
                }]}}
                """);

        assertThat(ElasticsearchGateway.parseHits(response)).singleElement().satisfies(candidate -> {
            assertThat(candidate.chunkId()).isEqualTo("chunk-1");
            assertThat(candidate.retrievalScore()).isEqualTo(7.25);
        });
    }
}
