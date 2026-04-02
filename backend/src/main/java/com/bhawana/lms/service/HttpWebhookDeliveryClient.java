package com.bhawana.lms.service;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;
import org.springframework.web.client.RestClient;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Component
public class HttpWebhookDeliveryClient implements WebhookDeliveryClient {

    private final RestClient restClient;

    public HttpWebhookDeliveryClient(RestClient.Builder restClientBuilder) {
        this.restClient = restClientBuilder.build();
    }

    @Override
    public WebhookDeliveryResponse deliver(WebhookDeliveryRequest request) {
        return restClient.post()
                .uri(request.endpointUrl())
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Correlation-Id", request.correlationId() == null ? "" : request.correlationId())
                .header("X-Webhook-Event", request.eventType())
                .header("X-Webhook-Delivery-Id", request.deliveryId())
                .header("X-Webhook-Timestamp", request.timestamp())
                .header("X-Webhook-Signature", request.signature())
                .body(request.payloadJson())
                .exchange((httpRequest, response) -> {
                    try {
                        String responseBody = response.getBody() == null
                                ? null
                                : StreamUtils.copyToString(response.getBody(), StandardCharsets.UTF_8);
                        return new WebhookDeliveryResponse(response.getStatusCode().value(), responseBody);
                    } catch (IOException exception) {
                        throw new IllegalStateException("Unable to read webhook delivery response body.", exception);
                    }
                });
    }
}
