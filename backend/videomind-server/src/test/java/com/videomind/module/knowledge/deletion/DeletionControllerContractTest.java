package com.videomind.module.knowledge.deletion;

import static org.assertj.core.api.Assertions.assertThat;

import com.videomind.module.knowledge.controller.KnowledgeBaseController;
import com.videomind.module.video.controller.VideoController;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

class DeletionControllerContractTest {

    @Test
    void knowledgeAndVideoDeletionReturnAccepted() throws Exception {
        assertAccepted(KnowledgeBaseController.class, "delete", Long.class);
        assertAccepted(VideoController.class, "delete", Long.class);
    }

    private static void assertAccepted(Class<?> controller, String method, Class<?>... parameters)
            throws NoSuchMethodException {
        ResponseStatus responseStatus = controller.getMethod(method, parameters)
                .getAnnotation(ResponseStatus.class);
        assertThat(responseStatus).isNotNull();
        assertThat(responseStatus.value()).isEqualTo(HttpStatus.ACCEPTED);
    }
}
