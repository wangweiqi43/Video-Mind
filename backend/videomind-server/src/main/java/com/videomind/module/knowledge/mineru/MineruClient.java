package com.videomind.module.knowledge.mineru;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class MineruClient {
    private static final int MAX_ENTRIES = 2_000;
    private static final long MAX_ENTRY_BYTES = 50L * 1024 * 1024;
    private static final long MAX_TOTAL_BYTES = 200L * 1024 * 1024;

    private final ObjectMapper mapper;
    private final HttpClient http;
    private final String localBaseUrl;
    private final String backend;
    private final int timeoutSeconds;
    private final Semaphore localSlots;

    public MineruClient(ObjectMapper mapper,
            @Value("${videomind.mineru.base-url:http://127.0.0.1:8000}") String localBaseUrl,
            @Value("${videomind.mineru.backend:pipeline}") String backend,
            @Value("${videomind.mineru.concurrency:1}") int concurrency,
            @Value("${videomind.mineru.timeout-seconds:300}") int timeoutSeconds) {
        this.mapper = mapper;
        this.localBaseUrl = localBaseUrl.replaceAll("/+$", "");
        this.backend = backend;
        this.timeoutSeconds = timeoutSeconds;
        this.localSlots = new Semaphore(Math.max(1, concurrency), true);
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public ParseResult parse(byte[] bytes, String filename, String resumeTaskId, ParseObserver observer) {
        boolean acquired = false;
        try {
            acquired = localSlots.tryAcquire(timeoutSeconds, TimeUnit.SECONDS);
            if (!acquired) {
                throw new MineruException("MINERU_LOCAL_BUSY", true, null);
            }
            return parseLocal(bytes, filename, resumeTaskId, observer == null ? ParseObserver.NOOP : observer);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new MineruException("MINERU_INTERRUPTED", true, interrupted);
        } catch (MineruException known) {
            throw known;
        } catch (Exception failure) {
            throw new MineruException("MINERU_LOCAL_UNAVAILABLE", true, failure);
        } finally {
            if (acquired) {
                localSlots.release();
            }
        }
    }

    private ParseResult parseLocal(byte[] bytes, String filename, String resumeTaskId,
                                   ParseObserver observer) throws Exception {
        String taskId = resumeTaskId;
        JsonNode resumeStatus = null;
        if (taskId != null && !taskId.isBlank()) {
            observer.beforePoll(taskId);
            resumeStatus = status(taskId);
            observer.beforePoll(taskId);
        }
        if (taskId == null || taskId.isBlank() || resumeStatus == null) {
            taskId = submit(bytes, filename);
            observer.submitted(taskId);
        }
        Instant deadline = Instant.now().plusSeconds(timeoutSeconds);
        String lastStatus = "";
        int lastQueued = -1;
        while (Instant.now().isBefore(deadline)) {
            observer.beforePoll(taskId);
            JsonNode value = status(taskId);
            observer.beforePoll(taskId);
            if (value == null) {
                taskId = submit(bytes, filename);
                observer.submitted(taskId);
                continue;
            }
            String state = value.path("status").asText();
            int queued = value.path("queued_ahead").asInt(0);
            if (!state.equals(lastStatus) || queued != lastQueued) {
                observer.status(taskId, state, queued);
                lastStatus = state;
                lastQueued = queued;
            }
            if ("failed".equalsIgnoreCase(state)) {
                throw new MineruException("MINERU_LOCAL_FAILED", true, null);
            }
            if ("completed".equalsIgnoreCase(state)) {
                return withTask(result(taskId), taskId);
            }
            Thread.sleep(500);
        }
        throw new MineruException("MINERU_LOCAL_TIMEOUT", true, null);
    }

    private String submit(byte[] bytes, String filename) throws Exception {
        String boundary = "VideoMind" + UUID.randomUUID();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        field(body, boundary, "backend", backend);
        field(body, boundary, "return_md", "true");
        field(body, boundary, "return_images", "true");
        field(body, boundary, "response_format_zip", "true");
        body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"files\"; filename=\""
                + safe(filename) + "\"\r\nContent-Type: application/octet-stream\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8));
        body.write(bytes);
        body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        HttpResponse<byte[]> response = http.send(HttpRequest.newBuilder(URI.create(localBaseUrl + "/tasks"))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build(),
                HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 202) {
            throw new IOException("MINERU_LOCAL_HTTP_" + response.statusCode());
        }
        String taskId = mapper.readTree(response.body()).path("task_id").asText();
        if (taskId.isBlank()) {
            throw new IOException("MINERU_LOCAL_TASK_ID_MISSING");
        }
        return taskId;
    }

    private JsonNode status(String taskId) throws Exception {
        HttpResponse<byte[]> response = http.send(HttpRequest.newBuilder(
                        URI.create(localBaseUrl + "/tasks/" + taskId)).timeout(Duration.ofSeconds(10)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() == 404) {
            return null;
        }
        if (response.statusCode() / 100 != 2) {
            throw new IOException("MINERU_LOCAL_STATUS_HTTP_" + response.statusCode());
        }
        return mapper.readTree(response.body());
    }

    private ParseResult result(String taskId) throws Exception {
        HttpResponse<byte[]> response = http.send(HttpRequest.newBuilder(
                        URI.create(localBaseUrl + "/tasks/" + taskId + "/result"))
                        .timeout(Duration.ofSeconds(60)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) {
            throw new IOException("MINERU_LOCAL_RESULT_HTTP_" + response.statusCode());
        }
        String type = response.headers().firstValue("Content-Type").orElse("");
        if (type.contains("zip") || isZip(response.body())) {
            return parseZip(response.body(), "MINERU_LOCAL");
        }
        JsonNode json = mapper.readTree(response.body());
        String markdown = json.path("markdown").asText(json.path("md_content").asText());
        if (markdown.isBlank()) {
            throw new IOException("MINERU_LOCAL_EMPTY");
        }
        return new ParseResult(markdown, "MINERU_LOCAL", List.of(), null);
    }

    static ParseResult parseZip(byte[] zip, String provider) throws IOException {
        Map<String, byte[]> files = new LinkedHashMap<>();
        long total = 0;
        int entries = 0;
        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(zip), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                if (++entries > MAX_ENTRIES) {
                    throw new IOException("MINERU_ZIP_TOO_MANY_ENTRIES");
                }
                String name = normalize(entry.getName());
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int read;
                long item = 0;
                while ((read = input.read(buffer)) >= 0) {
                    item += read;
                    total += read;
                    if (item > MAX_ENTRY_BYTES || total > MAX_TOTAL_BYTES) {
                        throw new IOException("MINERU_ZIP_SIZE_LIMIT");
                    }
                    output.write(buffer, 0, read);
                }
                files.put(name, output.toByteArray());
            }
        }
        String markdownName = files.keySet().stream()
                .filter(name -> name.toLowerCase(Locale.ROOT).endsWith(".md"))
                .findFirst().orElseThrow(() -> new IOException("MINERU_MARKDOWN_MISSING"));
        String markdown = new String(files.get(markdownName), StandardCharsets.UTF_8);
        List<Asset> assets = new ArrayList<>();
        int ordinal = 0;
        for (var file : files.entrySet()) {
            String lower = file.getKey().toLowerCase(Locale.ROOT);
            if (!(lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                    || lower.endsWith(".webp")) || !references(markdown, file.getKey())) {
                continue;
            }
            assets.add(new Asset(file.getKey(), file.getValue(), media(lower), assetType(lower), ordinal++));
        }
        return new ParseResult(markdown, provider, List.copyOf(assets), null);
    }

    private static String normalize(String name) throws IOException {
        String normalized = name.replace('\\', '/');
        if (normalized.startsWith("/") || normalized.matches("^[A-Za-z]:.*")
                || Arrays.asList(normalized.split("/")).contains("..")) {
            throw new IOException("MINERU_ZIP_UNSAFE_PATH");
        }
        return normalized;
    }

    private static boolean references(String markdown, String path) {
        String base = path.substring(path.lastIndexOf('/') + 1);
        return markdown.contains(path) || markdown.contains(base);
    }

    private static String media(String name) {
        return name.endsWith(".png") ? "image/png" : name.endsWith(".webp") ? "image/webp" : "image/jpeg";
    }

    private static String assetType(String name) {
        return name.contains("chart") ? "CHART" : "IMAGE";
    }

    private static boolean isZip(byte[] bytes) {
        return bytes != null && bytes.length > 3 && bytes[0] == 'P' && bytes[1] == 'K';
    }

    private static String safe(String filename) {
        return (filename == null ? "document" : filename).replaceAll("[\"\\r\\n]", "_");
    }

    private static void field(OutputStream output, String boundary, String name, String value) throws IOException {
        output.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name
                + "\"\r\n\r\n" + value + "\r\n").getBytes(StandardCharsets.UTF_8));
    }

    private static ParseResult withTask(ParseResult value, String taskId) {
        return new ParseResult(value.content(), value.parser(), value.assets(), taskId);
    }

    public interface ParseObserver {
        ParseObserver NOOP = new ParseObserver() {
        };

        default void submitted(String taskId) {
        }

        default void status(String taskId, String status, int queuedAhead) {
        }

        default void beforePoll(String taskId) {
        }
    }

    public static final class MineruException extends RuntimeException {
        private final String code;
        private final boolean retryable;

        public MineruException(String code, boolean retryable, Throwable cause) {
            super(code, cause);
            this.code = code;
            this.retryable = retryable;
        }

        public String code() {
            return code;
        }

        public boolean retryable() {
            return retryable;
        }
    }

    public record Asset(String path, byte[] bytes, String mediaType, String type, int ordinal) {
    }

    public record ParseResult(String content, String parser, List<Asset> assets, String taskId) {
    }
}
