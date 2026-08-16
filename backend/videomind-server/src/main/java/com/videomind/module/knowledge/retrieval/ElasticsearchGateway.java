package com.videomind.module.knowledge.retrieval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.videomind.config.ElasticsearchProperties;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class ElasticsearchGateway implements HybridSearchGateway, KnowledgeIndexGateway {
    private final ElasticsearchProperties properties;
    private final ObjectMapper mapper;
    private final HttpClient http;

    public ElasticsearchGateway(ElasticsearchProperties properties, ObjectMapper mapper) {
        this.properties = properties;
        this.mapper = mapper;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.getConnectTimeoutMillis()))
                .build();
    }

    @PostConstruct
    void initialize() {
        if (!properties.isEnabled()) {
            return;
        }
        try {
            ensureIndex();
        } catch (Exception failure) {
            log.warn("Elasticsearch initialization failed; retrieval will return 503: {}", failure.getMessage());
        }
    }

    @Override
    public void ensureIndex() {
        requireEnabled();
        if (status("HEAD", "/_alias/" + properties.getIndexAlias(), null, null) == 200) {
            return;
        }
        if (status("HEAD", "/" + properties.getPhysicalIndex(), null, null) == 404) {
            request("PUT", "/" + properties.getPhysicalIndex(),
                    indexDefinition(mapper, properties.getDimension()), "application/json");
        }
        ObjectNode aliases = mapper.createObjectNode();
        ObjectNode add = aliases.putArray("actions").addObject().putObject("add");
        add.put("index", properties.getPhysicalIndex());
        add.put("alias", properties.getIndexAlias());
        request("POST", "/_aliases", aliases, "application/json");
    }

    @Override
    public List<RetrievalCandidate> keywordSearch(Long userId, List<Long> knowledgeBaseIds,
                                                   String query, int limit) {
        JsonNode response = request("POST", "/" + properties.getIndexAlias() + "/_search",
                keywordBody(mapper, userId, knowledgeBaseIds, query, limit), "application/json");
        return parseHits(response);
    }

    @Override
    public List<RetrievalCandidate> vectorSearch(Long userId, List<Long> knowledgeBaseIds,
                                                  float[] vector, int limit) {
        JsonNode response = request("POST", "/" + properties.getIndexAlias() + "/_search",
                vectorBody(mapper, userId, knowledgeBaseIds, vector, limit, properties.getNumCandidates()),
                "application/json");
        return parseHits(response);
    }

    @Override
    public void stage(List<IndexedChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return;
        }
        StringBuilder ndjson = new StringBuilder();
        try {
            for (IndexedChunk indexed : chunks) {
                RetrievalCandidate value = indexed.candidate();
                ObjectNode action = mapper.createObjectNode();
                ObjectNode index = action.putObject("index");
                index.put("_index", properties.getIndexAlias());
                index.put("_id", value.embeddingId());
                ndjson.append(mapper.writeValueAsString(action)).append('\n');
                ndjson.append(mapper.writeValueAsString(indexSource(mapper, indexed))).append('\n');
            }
        } catch (IOException failure) {
            throw new ElasticsearchException("ELASTICSEARCH_BULK_SERIALIZE_FAILED", failure);
        }
        JsonNode response = request("POST", "/_bulk?refresh=wait_for", ndjson.toString(),
                "application/x-ndjson");
        if (response.path("errors").asBoolean(false)) {
            throw new ElasticsearchException("ELASTICSEARCH_BULK_PARTIAL_FAILURE", null);
        }
    }

    @Override
    public long countVersion(Long documentVersionId, boolean published) {
        ObjectNode body = mapper.createObjectNode();
        ArrayNode filters = body.putObject("query").putObject("bool").putArray("filter");
        filters.addObject().putObject("term").put("documentVersionId", documentVersionId);
        filters.addObject().putObject("term").put("published", published);
        return request("POST", "/" + properties.getIndexAlias() + "/_count", body, "application/json")
                .path("count").asLong();
    }

    @Override
    public void publishVersion(Long documentVersionId) {
        ObjectNode body = mapper.createObjectNode();
        body.putObject("script").put("source", "ctx._source.published = true");
        ArrayNode filters = body.putObject("query").putObject("bool").putArray("filter");
        filters.addObject().putObject("term").put("documentVersionId", documentVersionId);
        filters.addObject().putObject("term").put("published", false);
        request("POST", "/" + properties.getIndexAlias()
                + "/_update_by_query?refresh=true&conflicts=proceed", body, "application/json");
    }

    @Override
    public void deleteDocument(Long documentId) {
        ObjectNode body = mapper.createObjectNode();
        body.putObject("query").putObject("term").put("documentId", documentId);
        request("POST", "/" + properties.getIndexAlias()
                + "/_delete_by_query?refresh=true&conflicts=proceed", body, "application/json");
    }

    @Override
    public void deleteKnowledgeBase(Long knowledgeBaseId) {
        ObjectNode body = mapper.createObjectNode();
        body.putObject("query").putObject("term").put("knowledgeBaseId", knowledgeBaseId);
        request("POST", "/" + properties.getIndexAlias()
                + "/_delete_by_query?refresh=true&conflicts=proceed", body, "application/json");
    }

    @Override
    public void deleteOtherVersions(Long documentId, Long currentVersionId) {
        ObjectNode bool = mapper.createObjectNode();
        bool.putArray("filter").addObject().putObject("term").put("documentId", documentId);
        bool.putArray("must_not").addObject().putObject("term").put("documentVersionId", currentVersionId);
        ObjectNode body = mapper.createObjectNode();
        body.putObject("query").set("bool", bool);
        request("POST", "/" + properties.getIndexAlias()
                + "/_delete_by_query?refresh=true&conflicts=proceed", body, "application/json");
    }

    static ObjectNode indexDefinition(ObjectMapper mapper, int dimension) {
        ObjectNode root = mapper.createObjectNode();
        root.putObject("settings").put("index.refresh_interval", "1s");
        ObjectNode properties = root.putObject("mappings").putObject("properties");
        keyword(properties, "embeddingId");
        number(properties, "userId", "long");
        number(properties, "knowledgeBaseId", "long");
        number(properties, "documentId", "long");
        number(properties, "documentVersionId", "long");
        keyword(properties, "sourceType");
        number(properties, "chunkIndex", "integer");
        number(properties, "parentIndex", "integer");
        text(properties, "title");
        text(properties, "heading");
        text(properties, "content");
        properties.putObject("parentContent").put("type", "text").put("index", false);
        number(properties, "startMs", "long");
        number(properties, "endMs", "long");
        properties.putObject("published").put("type", "boolean");
        ObjectNode vector = properties.putObject("contentVector");
        vector.put("type", "dense_vector");
        vector.put("dims", dimension);
        vector.put("index", true);
        vector.put("similarity", "cosine");
        vector.putObject("index_options").put("type", "int8_hnsw");
        return root;
    }

    static ObjectNode keywordBody(ObjectMapper mapper, Long userId, List<Long> knowledgeBaseIds,
                                  String query, int limit) {
        ObjectNode root = mapper.createObjectNode();
        root.put("size", limit);
        ArrayNode source = root.putArray("_source");
        sourceFields().forEach(source::add);
        ObjectNode bool = root.putObject("query").putObject("bool");
        ObjectNode multi = bool.putArray("must").addObject().putObject("multi_match");
        multi.put("query", query);
        multi.putArray("fields").add("title^3").add("heading^2").add("content");
        filters(mapper, bool.putArray("filter"), userId, knowledgeBaseIds);
        return root;
    }

    static ObjectNode vectorBody(ObjectMapper mapper, Long userId, List<Long> knowledgeBaseIds,
                                 float[] vector, int limit, int numCandidates) {
        ObjectNode root = mapper.createObjectNode();
        root.put("size", limit);
        ArrayNode source = root.putArray("_source");
        sourceFields().forEach(source::add);
        ObjectNode knn = root.putObject("knn");
        knn.put("field", "contentVector");
        ArrayNode values = knn.putArray("query_vector");
        for (float item : vector) {
            values.add(item);
        }
        knn.put("k", limit);
        knn.put("num_candidates", Math.max(limit, numCandidates));
        ObjectNode bool = knn.putObject("filter").putObject("bool");
        filters(mapper, bool.putArray("filter"), userId, knowledgeBaseIds);
        return root;
    }

    private static void filters(ObjectMapper mapper, ArrayNode filters, Long userId,
                                List<Long> knowledgeBaseIds) {
        filters.addObject().putObject("term").put("userId", userId);
        ArrayNode ids = filters.addObject().putObject("terms").putArray("knowledgeBaseId");
        knowledgeBaseIds.forEach(ids::add);
        filters.addObject().putObject("term").put("published", true);
    }

    private static ObjectNode indexSource(ObjectMapper mapper, IndexedChunk indexed) {
        RetrievalCandidate value = indexed.candidate();
        ObjectNode source = mapper.createObjectNode();
        source.put("embeddingId", value.embeddingId());
        source.put("userId", indexed.userId());
        source.put("knowledgeBaseId", value.knowledgeBaseId());
        source.put("documentId", value.documentId());
        source.put("documentVersionId", value.documentVersionId());
        source.put("sourceType", indexed.sourceType());
        source.put("chunkIndex", value.chunkIndex());
        source.put("parentIndex", value.parentIndex());
        source.put("title", value.title());
        source.put("heading", value.heading());
        source.put("content", value.content());
        source.put("parentContent", value.parentContent());
        putNullable(source, "startMs", value.startMs());
        putNullable(source, "endMs", value.endMs());
        source.put("published", false);
        ArrayNode vector = source.putArray("contentVector");
        for (float item : indexed.vector()) {
            vector.add(item);
        }
        return source;
    }

    static List<RetrievalCandidate> parseHits(JsonNode response) {
        List<RetrievalCandidate> values = new ArrayList<>();
        for (JsonNode hit : response.path("hits").path("hits")) {
            JsonNode source = hit.path("_source");
            String embeddingId = source.path("embeddingId").asText(hit.path("_id").asText());
            if (embeddingId.isBlank()) {
                continue;
            }
            values.add(new RetrievalCandidate(embeddingId,
                    nullableLong(source, "knowledgeBaseId"), nullableLong(source, "documentId"),
                    nullableLong(source, "documentVersionId"), source.path("chunkIndex").asInt(),
                    source.path("parentIndex").asInt(), source.path("title").asText(null),
                    source.path("heading").asText(null), source.path("content").asText(""),
                    source.path("parentContent").asText(""), nullableLong(source, "startMs"),
                    nullableLong(source, "endMs"), hit.path("_score").asDouble(0)));
        }
        return List.copyOf(values);
    }

    private JsonNode request(String method, String path, JsonNode body, String contentType) {
        try {
            return request(method, path, body == null ? null : mapper.writeValueAsString(body), contentType);
        } catch (IOException failure) {
            throw new ElasticsearchException("ELASTICSEARCH_REQUEST_SERIALIZE_FAILED", failure);
        }
    }

    private JsonNode request(String method, String path, String body, String contentType) {
        requireEnabled();
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl() + path))
                    .timeout(Duration.ofMillis(properties.getReadTimeoutMillis()))
                    .header("Accept", "application/json");
            authorize(request);
            if (body == null) {
                request.method(method, HttpRequest.BodyPublishers.noBody());
            } else {
                request.header("Content-Type", contentType)
                        .method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
            }
            HttpResponse<String> response = http.send(request.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() / 100 != 2) {
                throw new ElasticsearchException("ELASTICSEARCH_HTTP_" + response.statusCode(), null);
            }
            return response.body().isBlank() ? mapper.createObjectNode() : mapper.readTree(response.body());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new ElasticsearchException("ELASTICSEARCH_INTERRUPTED", interrupted);
        } catch (IOException failure) {
            throw new ElasticsearchException("ELASTICSEARCH_UNAVAILABLE", failure);
        }
    }

    private int status(String method, String path, String body, String contentType) {
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl() + path))
                    .timeout(Duration.ofMillis(properties.getReadTimeoutMillis()));
            authorize(request);
            request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body));
            return http.send(request.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
        } catch (Exception failure) {
            throw new ElasticsearchException("ELASTICSEARCH_UNAVAILABLE", failure);
        }
    }

    private void authorize(HttpRequest.Builder request) {
        if (properties.getUsername() != null && !properties.getUsername().isBlank()) {
            String value = properties.getUsername() + ":" + (properties.getPassword() == null
                    ? "" : properties.getPassword());
            request.header("Authorization", "Basic " + Base64.getEncoder()
                    .encodeToString(value.getBytes(StandardCharsets.UTF_8)));
        }
    }

    private String baseUrl() {
        return properties.getUrl().replaceAll("/+$", "");
    }

    private void requireEnabled() {
        if (!properties.isEnabled()) {
            throw new ElasticsearchException("ELASTICSEARCH_DISABLED", null);
        }
    }

    private static void keyword(ObjectNode properties, String name) {
        properties.putObject(name).put("type", "keyword");
    }

    private static void number(ObjectNode properties, String name, String type) {
        properties.putObject(name).put("type", type);
    }

    private static void text(ObjectNode properties, String name) {
        ObjectNode field = properties.putObject(name);
        field.put("type", "text");
        field.put("analyzer", "ik_max_word");
        field.put("search_analyzer", "ik_smart");
    }

    private static List<String> sourceFields() {
        return List.of("embeddingId", "knowledgeBaseId", "documentId", "documentVersionId", "chunkIndex",
                "parentIndex", "title", "heading", "content", "parentContent", "startMs", "endMs");
    }

    private static void putNullable(ObjectNode value, String field, Long number) {
        if (number == null) {
            value.putNull(field);
        } else {
            value.put(field, number);
        }
    }

    private static Long nullableLong(JsonNode source, String field) {
        JsonNode value = source.get(field);
        return value == null || value.isNull() ? null : value.asLong();
    }

    public static final class ElasticsearchException extends RuntimeException {
        public ElasticsearchException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
