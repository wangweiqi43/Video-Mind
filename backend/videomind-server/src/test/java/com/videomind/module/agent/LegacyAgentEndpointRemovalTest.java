package com.videomind.module.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

class LegacyAgentEndpointRemovalTest {

    private static final List<String> FORBIDDEN_PATHS = List.of(
            "/integrations/mindagent",
            "/agent/webhooks",
            "/v1/system/capabilities",
            "advanced-report",
            "presentations",
            "/knowledge/vectorize",
            "/knowledge/status",
            "/knowledge/tasks/"
    );

    @Test
    void legacyRemoteAgentAndRedisearchEndpointsHaveNoControllerMappings() throws Exception {
        List<String> mappings = controllerMappings();

        assertThat(mappings)
                .noneMatch(path -> FORBIDDEN_PATHS.stream().anyMatch(path::contains));
    }

    private List<String> controllerMappings() throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        List<String> mappings = new ArrayList<>();
        for (var component : scanner.findCandidateComponents("com.videomind")) {
            Class<?> controller = Class.forName(component.getBeanClassName());
            List<String> bases = paths(AnnotatedElementUtils.findMergedAnnotation(controller, RequestMapping.class));
            for (Method method : controller.getDeclaredMethods()) {
                RequestMapping route = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
                if (route == null) continue;
                for (String base : bases) {
                    for (String suffix : paths(route)) mappings.add(base + suffix);
                }
            }
        }
        return mappings;
    }

    private List<String> paths(RequestMapping mapping) {
        if (mapping == null) return List.of("");
        String[] values = mapping.path().length == 0 ? mapping.value() : mapping.path();
        return values.length == 0 ? List.of("") : Arrays.asList(values);
    }
}
