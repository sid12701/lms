package com.bhawana.lms.service;

public interface WebhookDeliveryClient {

    WebhookDeliveryResponse deliver(String endpointUrl, String payloadJson, String correlationId);

    record WebhookDeliveryResponse(int statusCode, String responseBody) {
    }
}
