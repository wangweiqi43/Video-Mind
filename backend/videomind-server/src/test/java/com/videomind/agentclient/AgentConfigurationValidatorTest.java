package com.videomind.agentclient;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class AgentConfigurationValidatorTest {

    @Test
    void allowsMissingOAuthSecretsWhenAgentIsDisabled() {
        AgentClientProperties properties = new AgentClientProperties();

        assertThatCode(() -> new AgentConfigurationValidator(properties).validate()).doesNotThrowAnyException();
    }

    @Test
    void rejectsEnabledAgentWithoutRequiredSecrets() {
        AgentClientProperties properties = new AgentClientProperties();
        properties.setEnabled(true);

        assertThatThrownBy(() -> new AgentConfigurationValidator(properties).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AGENT_PLATFORM_OAUTH_CLIENT_SECRET");
    }

    @Test
    void acceptsCompleteOAuthConfiguration() {
        AgentClientProperties properties = completeProperties();

        assertThatCode(() -> new AgentConfigurationValidator(properties).validate()).doesNotThrowAnyException();
    }

    @Test
    void rejectsInvalidFeatureDependenciesAndParameters() {
        AgentClientProperties properties = completeProperties();
        properties.setWebSearchEnabled(true);

        assertThatThrownBy(() -> new AgentConfigurationValidator(properties).validate())
                .hasMessageContaining("VIDEOMIND_AGENT_WEB_SEARCH_ENABLED");

        properties.setWebSearchEnabled(false);
        properties.setMaxRetries(-1);
        assertThatThrownBy(() -> new AgentConfigurationValidator(properties).validate())
                .hasMessageContaining("AGENT_PLATFORM_MAX_RETRIES");
    }

    private AgentClientProperties completeProperties() {
        AgentClientProperties properties = new AgentClientProperties();
        properties.setEnabled(true);
        properties.setBaseUrl("http://localhost:8090");
        properties.setFrontendUrl("http://localhost:5174");
        properties.setOauthClientId("videomind");
        properties.setOauthClientSecret("test-oauth-secret");
        properties.setOauthRedirectUri("http://localhost:8080/api/integrations/mindagent/callback");
        properties.setWebhookSecret("test-webhook-secret");
        return properties;
    }
}
