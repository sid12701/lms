package com.bhawana.lms.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class WebhookDispatchConfig {

    @Bean(name = "webhookDeliveryExecutor")
    public ThreadPoolTaskExecutor webhookDeliveryExecutor(
            @Value("${app.webhooks.delivery.thread-pool-size:10}") int poolSize
    ) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(poolSize);
        executor.setMaxPoolSize(poolSize);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("webhook-delivery-");
        executor.initialize();
        return executor;
    }
}
