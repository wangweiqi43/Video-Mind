package com.videomind.module.knowledge.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.videomind.common.enums.KnowledgeLifecycleStatus;
import com.videomind.module.knowledge.dto.DocumentUploadResponse;
import com.videomind.module.knowledge.service.DocumentUploadService;
import com.videomind.module.task.mq.TaskDispatchResult;
import com.videomind.module.task.mq.TransactionalTaskMessageProducer;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

class KnowledgeDocumentApplicationServiceImplTest {
    private final DocumentUploadService uploads = mock(DocumentUploadService.class);
    private final TransactionalTaskMessageProducer messages = mock(TransactionalTaskMessageProducer.class);
    private final KnowledgeDocumentApplicationServiceImpl service =
            new KnowledgeDocumentApplicationServiceImpl(uploads, messages);
    private final MockMultipartFile file = new MockMultipartFile("file", "manual.md", "text/markdown",
            "content".getBytes(java.nio.charset.StandardCharsets.UTF_8));

    @Test
    void dispatchesOnlyAfterRegistrationReturnsAndExposesTaskIdentity() {
        when(uploads.upload(7L, 11L, file)).thenReturn(response(KnowledgeLifecycleStatus.PROCESSING, false));
        when(messages.dispatch(any())).thenReturn(new TaskDispatchResult("event-1", 99L, 21L, false));

        DocumentUploadResponse result = service.uploadAndDispatch(7L, 11L, file);

        var order = inOrder(uploads, messages);
        order.verify(uploads).upload(7L, 11L, file);
        order.verify(messages).dispatch(any());
        assertThat(result.eventId()).isEqualTo("event-1");
        assertThat(result.taskId()).isEqualTo(99L);
        assertThat(result.reusedTask()).isFalse();
    }

    @Test
    void readyShaDuplicateDoesNotCreateAnotherMessage() {
        when(uploads.upload(7L, 11L, file)).thenReturn(response(KnowledgeLifecycleStatus.READY, true));

        DocumentUploadResponse result = service.uploadAndDispatch(7L, 11L, file);

        assertThat(result.duplicated()).isTrue();
        assertThat(result.taskId()).isNull();
        verify(messages, never()).dispatch(any());
    }

    @Test
    void processingShaDuplicateRedispatchesAndReusesActiveTaskFingerprint() {
        when(uploads.upload(7L, 11L, file)).thenReturn(response(KnowledgeLifecycleStatus.PROCESSING, true));
        when(messages.dispatch(any())).thenReturn(new TaskDispatchResult("event-2", 88L, 21L, true));

        DocumentUploadResponse result = service.uploadAndDispatch(7L, 11L, file);

        assertThat(result.taskId()).isEqualTo(88L);
        assertThat(result.reusedTask()).isTrue();
    }

    private static DocumentUploadResponse response(KnowledgeLifecycleStatus status, boolean duplicate) {
        return new DocumentUploadResponse(31L, 32L, "manual.md", "sha", status, "PARSE", duplicate,
                null, null, false);
    }
}
