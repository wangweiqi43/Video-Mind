package com.videomind.module.knowledge.deletion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.videomind.config.ElasticsearchProperties;
import com.videomind.config.MinioProperties;
import com.videomind.infrastructure.storage.impl.MinioObjectStorageService;
import com.videomind.module.knowledge.retrieval.ElasticsearchGateway;
import com.videomind.module.knowledge.retrieval.KnowledgeIndexGateway.IndexedChunk;
import com.videomind.module.knowledge.retrieval.RetrievalCandidate;
import io.minio.MinioClient;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class ExternalDeletionIntegrationTest {

    @Test
    void deletesOnlyTheSelectedKnowledgeBaseFromRealElasticsearch() {
        assumeTrue(enabled("VIDEOMIND_DELETION_ES_INTEGRATION"),
                "set VIDEOMIND_DELETION_ES_INTEGRATION=true for isolated Elasticsearch deletion test");
        long suffix = Math.abs(System.nanoTime());
        long knowledgeBaseId = 8_000_000_000L + suffix % 100_000_000L;
        long documentId = knowledgeBaseId + 1;
        long versionId = knowledgeBaseId + 2;
        String embeddingId = "deletion-it-" + suffix;
        ElasticsearchProperties properties = new ElasticsearchProperties();
        properties.setUrl(env("ELASTICSEARCH_URL", "http://127.0.0.1:9201"));
        properties.setIndexAlias(env("ELASTICSEARCH_INDEX_ALIAS", "videomind-chunks"));
        properties.setPhysicalIndex(env("ELASTICSEARCH_PHYSICAL_INDEX", "videomind-chunks-v1"));
        properties.setDimension(1024);
        ElasticsearchGateway gateway = new ElasticsearchGateway(properties, new ObjectMapper());
        RetrievalCandidate candidate = new RetrievalCandidate(embeddingId, knowledgeBaseId,
                documentId, versionId, 0, 0, "deletion integration", null,
                "isolated deletion integration content", "", null, null);
        float[] vector = new float[1024];
        vector[0] = 1.0F;
        try {
            gateway.ensureIndex();
            gateway.stage(List.of(new IndexedChunk(7L, candidate, vector, "DOCUMENT")));
            assertThat(gateway.countVersion(versionId, false)).isEqualTo(1);

            gateway.deleteKnowledgeBase(knowledgeBaseId);
            gateway.deleteKnowledgeBase(knowledgeBaseId);

            assertThat(gateway.countVersion(versionId, false)).isZero();
        } finally {
            gateway.deleteDocument(documentId);
        }
    }

    @Test
    void removesAUniqueObjectTwiceFromRealMinio() {
        assumeTrue(enabled("VIDEOMIND_DELETION_MINIO_INTEGRATION"),
                "set VIDEOMIND_DELETION_MINIO_INTEGRATION=true for isolated MinIO deletion test");
        String endpoint = env("MINIO_ENDPOINT", "http://127.0.0.1:9000");
        String accessKey = env("MINIO_ACCESS_KEY", "minioadmin");
        String secretKey = env("MINIO_SECRET_KEY", "minioadmin");
        String bucket = env("MINIO_BUCKET", "videomind-videos");
        String objectKey = "integration/deletion/" + Long.toUnsignedString(System.nanoTime(), 36) + ".txt";
        MinioProperties properties = new MinioProperties();
        properties.setEndpoint(endpoint);
        properties.setPresignEndpoint(endpoint);
        properties.setAccessKey(accessKey);
        properties.setSecretKey(secretKey);
        properties.setBucket(bucket);
        MinioClient client = MinioClient.builder().endpoint(endpoint)
                .credentials(accessKey, secretKey).build();
        MinioObjectStorageService storage = new MinioObjectStorageService(client, properties);
        byte[] bytes = "isolated deletion integration".getBytes(StandardCharsets.UTF_8);
        try {
            storage.putObject(objectKey, new ByteArrayInputStream(bytes), bytes.length, "text/plain");
            assertThat(storage.objectExists(bucket, objectKey)).isTrue();

            storage.removeObject(bucket, objectKey);
            storage.removeObject(bucket, objectKey);

            assertThat(storage.objectExists(bucket, objectKey)).isFalse();
        } finally {
            storage.removeObject(bucket, objectKey);
        }
    }

    private static boolean enabled(String name) {
        return Boolean.parseBoolean(System.getenv(name)) || Boolean.getBoolean(name);
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
