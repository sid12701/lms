package com.bhawana.lms.security;

import io.lettuce.core.AbstractRedisClient;
import com.bhawana.lms.service.OpsAlertEmitters;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

/**
 * Rate-limit wiring (M10). The rate limiter reuses the auto-configured
 * {@link LettuceConnectionFactory}'s native Lettuce client, so every validated
 * {@code spring.data.redis.*} option — username/password, TLS ({@code ssl.enabled} /
 * {@code ssl.bundle}), database, client name, connect timeout and the command timeout that
 * bounds every rate-limit round-trip — applies to the limiter instead of being silently
 * dropped by a hand-rolled {@code redis://host:port} URI.
 *
 * <p>Nothing connects to Redis at startup: {@link RateLimitRedisConnectionProvider} dials
 * lazily on first rate-limited request and retries on a cooldown, so an unavailable store at
 * boot is a per-route outage-policy decision (see {@code RateLimitRule.onStoreFailure}),
 * not a failed context — and recovery needs no restart.
 */
@Configuration
@ConditionalOnProperty(name = "app.rate-limit.enabled", havingValue = "true", matchIfMissing = true)
public class RateLimitConfig {

    @Bean(destroyMethod = "close")
    RateLimitRedisConnectionProvider rateLimitRedisConnectionProvider(
            RedisConnectionFactory redisConnectionFactory,
            RateLimitProperties properties
    ) {
        if (!(redisConnectionFactory instanceof LettuceConnectionFactory lettuceConnectionFactory)) {
            throw new IllegalStateException(
                    "app.rate-limit.enabled=true requires the Lettuce-based RedisConnectionFactory "
                            + "(spring.data.redis.client-type=lettuce, the default); found "
                            + redisConnectionFactory.getClass().getName()
            );
        }
        AbstractRedisClient nativeClient = lettuceConnectionFactory.getNativeClient();
        if (nativeClient == null) {
            throw new IllegalStateException(
                    "LettuceConnectionFactory has no native client — check spring.data.redis.* configuration."
            );
        }
        return new RateLimitRedisConnectionProvider(nativeClient, properties.getReconnectInterval());
    }

    @Bean
    public RateLimitFilter rateLimitFilter(
            RateLimitRedisConnectionProvider rateLimitRedisConnectionProvider,
            ObjectMapper objectMapper,
            RateLimitProperties properties,
            ObjectProvider<OpsAlertEmitters> opsAlertEmittersProvider,
            MeterRegistry meterRegistry
    ) {
        return new RateLimitFilter(
                rateLimitRedisConnectionProvider::proxyManager,
                objectMapper,
                properties,
                opsAlertEmittersProvider,
                meterRegistry
        );
    }
}
