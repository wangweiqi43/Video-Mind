package com.videomind.agentclient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.videomind.module.agent.service.MindAgentOAuthService;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AgentApiClientAuthorizationTest {

    private HttpServer server;
    private AgentClientProperties properties;
    private MindAgentOAuthService bindingService;
    private AtomicInteger requests;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        requests = new AtomicInteger();
        properties = new AgentClientProperties();
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setMaxRetries(0);
        bindingService = mock(MindAgentOAuthService.class);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void postRefreshesAndReplaysOnceWithSameIdempotencyKey() {
        List<String> authorizations = new ArrayList<>();
        List<String> idempotencyKeys = new ArrayList<>();
        List<String> bodies = new ArrayList<>();
        server.createContext("/v1/test", exchange -> {
            int request = requests.incrementAndGet();
            authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
            idempotencyKeys.add(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, request == 1 ? 401 : 200,
                    request == 1 ? "{\"code\":\"ACCESS_TOKEN_EXPIRED\"}" : "{\"ok\":true}",
                    "application/json");
        });
        server.start();
        when(bindingService.accessToken(7L)).thenReturn("old-access", "new-access");
        when(bindingService.refreshAfterUnauthorized(7L, "old-access")).thenReturn("new-access");

        AgentApiClient client = client();
        assertThat(client.post("/v1/test", java.util.Map.of("value", 1), context()).path("ok").asBoolean()).isTrue();

        assertThat(requests).hasValue(2);
        assertThat(authorizations).containsExactly("Bearer old-access", "Bearer new-access");
        assertThat(idempotencyKeys).containsExactly("idem-1", "idem-1");
        assertThat(bodies.get(0)).isEqualTo(bodies.get(1));
        verify(bindingService).refreshAfterUnauthorized(7L, "old-access");
    }

    @Test
    void getStopsAfterSecondUnauthorizedResponse() {
        server.createContext("/v1/test", exchange -> {
            requests.incrementAndGet();
            respond(exchange, 401, "{\"code\":\"ACCESS_TOKEN_EXPIRED\",\"message\":\"expired\"}",
                    "application/json");
        });
        server.start();
        when(bindingService.accessToken(7L)).thenReturn("old-access", "new-access");
        when(bindingService.refreshAfterUnauthorized(7L, "old-access")).thenReturn("new-access");

        assertThatThrownBy(() -> client().get("/v1/test", context()))
                .isInstanceOfSatisfying(AgentClientException.class,
                        error -> assertThat(error.getHttpStatus()).isEqualTo(401));
        assertThat(requests).hasValue(2);
    }

    @Test
    void getRefreshesWhenMindAgentReturnsForbiddenForInvalidJwt() {
        server.createContext("/v1/userinfo", exchange -> {
            int request = requests.incrementAndGet();
            respond(exchange, request == 1 ? 403 : 200,
                    request == 1 ? "{\"code\":\"FORBIDDEN\"}" : "{\"sub\":\"subject-1\"}",
                    "application/json");
        });
        server.start();
        when(bindingService.accessToken(7L)).thenReturn("invalid-access", "refreshed-access");
        when(bindingService.refreshAfterUnauthorized(7L, "invalid-access")).thenReturn("refreshed-access");

        assertThat(client().get("/v1/userinfo", context()).path("sub").asText()).isEqualTo("subject-1");
        assertThat(requests).hasValue(2);
        verify(bindingService).refreshAfterUnauthorized(7L, "invalid-access");
    }

    @Test
    void sseClosesUnauthorizedResponseThenStreamsReplay() {
        server.createContext("/v1/stream", exchange -> {
            int request = requests.incrementAndGet();
            respond(exchange, request == 1 ? 401 : 200,
                    request == 1 ? "unauthorized" : "event: delta\ndata: hello\n\nevent: done\ndata: {}\n\n",
                    request == 1 ? "application/json" : "text/event-stream");
        });
        server.start();
        when(bindingService.accessToken(7L)).thenReturn("old-access", "new-access");
        when(bindingService.refreshAfterUnauthorized(7L, "old-access")).thenReturn("new-access");
        List<AgentApiClient.AgentSseEvent> events = new ArrayList<>();

        client().postSse("/v1/stream", java.util.Map.of("message", "hello"), context(), events::add);

        assertThat(requests).hasValue(2);
        assertThat(events).extracting(AgentApiClient.AgentSseEvent::event).containsExactly("delta", "done");
        assertThat(events).extracting(AgentApiClient.AgentSseEvent::data).containsExactly("hello", "{}");
    }

    private AgentApiClient client() {
        return new AgentApiClient(HttpClient.newHttpClient(), new ObjectMapper(), properties, bindingService);
    }

    private AgentRequestContext context() {
        return AgentRequestContext.of("videomind", 7L, "idem-1", "trace-1");
    }

    private void respond(HttpExchange exchange, int status, String body, String contentType) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
