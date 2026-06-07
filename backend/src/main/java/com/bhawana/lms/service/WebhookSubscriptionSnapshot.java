package com.bhawana.lms.service;

import com.bhawana.lms.domain.Lsp;
import com.bhawana.lms.domain.WebhookEventType;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public record WebhookSubscriptionSnapshot(
        boolean enabled,
        String endpointUrl,
        String signingSecret,
        Set<WebhookEventType> eventTypes
) {

    static WebhookSubscriptionSnapshot from(Lsp lsp) {
        return new WebhookSubscriptionSnapshot(
                lsp.isWebhookEnabled(),
                lsp.getWebhookEndpointUrl(),
                lsp.getWebhookSigningSecret(),
                new LinkedHashSet<>(lsp.getWebhookEventTypes())
        );
    }

    boolean urlChanged(WebhookSubscriptionSnapshot after) {
        return !Objects.equals(normalizeUrl(endpointUrl), normalizeUrl(after.endpointUrl));
    }

    boolean signingSecretChanged(WebhookSubscriptionSnapshot after) {
        return !Objects.equals(signingSecret, after.signingSecret);
    }

    boolean enabledChanged(WebhookSubscriptionSnapshot after) {
        return enabled != after.enabled;
    }

    boolean eventTypesChanged(WebhookSubscriptionSnapshot after) {
        return !eventTypes.equals(after.eventTypes);
    }

    private static String normalizeUrl(String url) {
        return url == null || url.isBlank() ? null : url.trim();
    }

    Set<String> eventTypeNames() {
        return eventTypes.stream().map(Enum::name).collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
