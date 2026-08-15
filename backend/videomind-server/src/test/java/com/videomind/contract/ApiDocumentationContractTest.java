package com.videomind.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class ApiDocumentationContractTest {
    private static final Path REPOSITORY_ROOT = Path.of("..", "..").toAbsolutePath().normalize();

    @Test
    void openApiIsValidYamlAndListsOnlyCurrentPublicRoutes() throws IOException {
        Map<String, Object> document;
        try (Reader reader = Files.newBufferedReader(REPOSITORY_ROOT.resolve("openapi.yaml"),
                StandardCharsets.UTF_8)) {
            document = new Yaml().load(reader);
        }

        assertThat(document.get("openapi")).isEqualTo("3.0.3");
        @SuppressWarnings("unchecked")
        Map<String, Object> paths = (Map<String, Object>) document.get("paths");
        assertThat(paths.keySet()).containsExactlyInAnyOrderElementsOf(currentRoutes());
        assertThat(paths).doesNotContainKeys(
                "/api/tasks/{taskId}/cancel",
                "/api/knowledge/vectorize/{taskId}",
                "/api/knowledge/status/{taskId}");
    }

    @Test
    void readmeAdvertisesAnswerCancellationInsteadOfVideoTaskCancellation() throws IOException {
        String readme = Files.readString(REPOSITORY_ROOT.resolve("README.md"), StandardCharsets.UTF_8);

        assertThat(readme).contains("/api/chat/generations/{generationId}/cancel");
        assertThat(readme).doesNotContain("| `/api/tasks/{taskId}/cancel`");
    }

    private Set<String> currentRoutes() {
        return Set.of(
                "/api/auth/register",
                "/api/auth/login",
                "/api/auth/refresh",
                "/api/auth/logout",
                "/api/auth/me",
                "/api/videos/upload",
                "/api/videos/check-md5",
                "/api/videos/multipart/init",
                "/api/videos/multipart/{uploadId}/chunk",
                "/api/videos/multipart/{uploadId}/complete",
                "/api/videos/multipart/{uploadId}/status",
                "/api/videos/list",
                "/api/videos/{videoId}",
                "/api/videos/{videoId}/transcription",
                "/api/videos/{videoId}/stream",
                "/api/tasks/analyze",
                "/api/tasks/{taskId}",
                "/api/tasks/{taskId}/result",
                "/api/tasks/video/{videoId}/latest-success",
                "/api/knowledge-bases",
                "/api/knowledge-bases/{knowledgeBaseId}",
                "/api/knowledge-bases/{knowledgeBaseId}/documents",
                "/api/chat/session",
                "/api/chat/session/list",
                "/api/chat/session/{sessionId}/messages",
                "/api/chat/message",
                "/api/chat/message/stream",
                "/api/chat/generations/{generationId}/cancel",
                "/actuator/health");
    }
}
