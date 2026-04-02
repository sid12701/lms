package com.bhawana.lms.service;

public interface WebhookDeliveryClient {

    WebhookDeliveryResponse deliver(WebhookDeliveryRequest request);

    record WebhookDeliveryRequest(
            String endpointUrl,
            String payloadJson,
            String correlationId,
            String eventType,
            String deliveryId,
            String timestamp,
            String signature
    ) {
    }

    record WebhookDeliveryResponse(int statusCode, String responseBody) {
    }
}
