package com.videomind.module.agent.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.videomind.agentclient.AgentWebhookVerifier;
import com.videomind.common.exception.BizException;
import com.videomind.module.agent.service.AgentWebhookService;
import org.junit.jupiter.api.Test;

class AgentWebhookControllerTest {

    private final AgentWebhookVerifier verifier = mock(AgentWebhookVerifier.class);
    private final AgentWebhookService service = mock(AgentWebhookService.class);
    private final AgentWebhookController controller = new AgentWebhookController(verifier, service);

    @Test
    void invalidSignatureReturnsRealUnauthorizedStatus() {
        when(verifier.verify("1", "{}", "bad")).thenReturn(false);

        assertThat(controller.task("1", "bad", "{}").getStatusCode().value()).isEqualTo(401);
        verify(service, never()).handle("{}");
    }

    @Test
    void unknownTaskAndInternalFailureReturnRealErrorStatuses() {
        when(verifier.verify("1", "{}", "ok")).thenReturn(true);
        doThrow(new BizException(404, "not found")).when(service).handle("{}");
        assertThat(controller.task("1", "ok", "{}").getStatusCode().value()).isEqualTo(404);

        doThrow(new IllegalStateException("boom")).when(service).handle("bad");
        when(verifier.verify("1", "bad", "ok")).thenReturn(true);
        assertThat(controller.task("1", "ok", "bad").getStatusCode().value()).isEqualTo(500);
    }
}
