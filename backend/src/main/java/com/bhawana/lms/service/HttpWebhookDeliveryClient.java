package com.bhawana.lms.service;

import com.bhawana.lms.security.SsrfSafeUrlValidator;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class HttpWebhookDeliveryClient implements WebhookDeliveryClient {

    private static final int MAX_RESPONSE_BYTES = 65_536;

    private final RestClient restClient;

    public HttpWebhookDeliveryClient(RestClient.Builder restClientBuilder) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(5));
        requestFactory.setReadTimeout(Duration.ofSeconds(10));
        this.restClient = restClientBuilder.requestFactory(requestFactory).build();
    }

    @Override
    public WebhookDeliveryResponse deliver(WebhookDeliveryRequest request) {
        SsrfSafeUrlValidator.validate(request.endpointUrl());

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
                        String responseBody = null;
                        if (response.getBody() != null) {
                            byte[] limited = response.getBody().readNBytes(MAX_RESPONSE_BYTES);
                            responseBody = new String(limited, StandardCharsets.UTF_8);
                        }
                        return new WebhookDeliveryResponse(response.getStatusCode().value(), responseBody);
                    } catch (IOException exception) {
                        throw new IllegalStateException("Unable to read webhook delivery response body.", exception);
                    }
                });
    }
}
