package com.bhawana.lms.security;

import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.redis.lettuce.Bucket4jLettuce;
import io.lettuce.core.AbstractRedisClient;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.cluster.RedisClusterClient;
import io.lettuce.core.cluster.api.StatefulRedisClusterConnection;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.codec.RedisCodec;
import io.lettuce.core.codec.StringCodec;
import java.time.Duration;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * M10: owns the single dedicated Lettuce connection Bucket4j talks over, so the rate limiter
 * no longer dies with the application context when Redis is unreachable at startup, and never
 * needs an instance restart to recover.
 *
 * <p>The underlying {@link AbstractRedisClient} is borrowed from the auto-configured
 * {@code LettuceConnectionFactory} — which is where credentials, TLS, connect/command
 * timeouts and client naming from {@code spring.data.redis.*} already live — but this class
 * deliberately owns the connection lifecycle itself:
 *
 * <ul>
 *   <li>{@link #proxyManager()} connects lazily on first use and retries after
 *       {@code app.rate-limit.reconnect-interval} on failure, returning {@code Optional.empty()}
 *       while the store is unreachable so the filter can apply the per-route outage policy
 *       instead of blocking or failing the process.
 *   <li>Once a connection exists, Lettuce's own auto-reconnect handles mid-run outages; in
 *       between, commands fail fast on the {@code spring.data.redis.timeout} command deadline,
 *       which the filter again maps onto the outage policy.
 *   <li>{@link #close()} (bean shutdown) closes only the connection — never the shared client,
 *       which belongs to the connection factory's lifecycle.
 * </ul>
 */
final class RateLimitRedisConnectionProvider implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RateLimitRedisConnectionProvider.class);
    private static final RedisCodec<String, byte[]> CODEC =
            RedisCodec.of(StringCodec.UTF8, ByteArrayCodec.INSTANCE);
    private static final Duration KEY_TTL = Duration.ofMinutes(10);

    private final AbstractRedisClient redisClient;
    private final Duration reconnectInterval;
    private final Object connectLock = new Object();

    private volatile ProxyManager<String> proxyManager;
    private io.lettuce.core.api.StatefulConnection<String, byte[]> connection;
    private long nextConnectAttemptNanos;
    private boolean closed;

    RateLimitRedisConnectionProvider(AbstractRedisClient redisClient, Duration reconnectInterval) {
        this.redisClient = redisClient;
        this.reconnectInterval = reconnectInterval;
    }

    /**
     * The Bucket4j proxy manager once a connection exists, or empty while Redis cannot be
     * reached (startup outage, wrong credentials, TLS failure). Retries are rate-limited by
     * the reconnect interval so a hard-down Redis is not re-dialed on every request.
     */
    Optional<ProxyManager<String>> proxyManager() {
        ProxyManager<String> current = proxyManager;
        if (current != null) {
            return Optional.of(current);
        }
        synchronized (connectLock) {
            if (closed) {
                return Optional.empty();
            }
            if (proxyManager != null) {
                return Optional.of(proxyManager);
            }
            long now = System.nanoTime();
            if (now < nextConnectAttemptNanos) {
                return Optional.empty();
            }
            nextConnectAttemptNanos = now + reconnectInterval.toNanos();
            try {
                connection = connect();
                proxyManager = buildProxyManager(connection);
                log.info("rate_limit_redis_connected — rate limiting store is reachable");
                return Optional.of(proxyManager);
            } catch (RuntimeException exception) {
                log.warn(
                        "rate_limit_redis_connect_failed — rate limiting store unreachable; "
                                + "retrying in {}ms and applying per-route outage policy meanwhile",
                        reconnectInterval.toMillis(),
                        exception
                );
                return Optional.empty();
            }
        }
    }

    private io.lettuce.core.api.StatefulConnection<String, byte[]> connect() {
        if (redisClient instanceof RedisClient standalone) {
            return standalone.connect(CODEC);
        }
        if (redisClient instanceof RedisClusterClient cluster) {
            return cluster.connect(CODEC);
        }
        throw new IllegalStateException(
                "Unsupported Redis client type for rate limiting: " + redisClient.getClass().getName()
        );
    }

    private static ProxyManager<String> buildProxyManager(
            io.lettuce.core.api.StatefulConnection<String, byte[]> connection
    ) {
        if (connection instanceof StatefulRedisClusterConnection<String, byte[]> clusterConnection) {
            return Bucket4jLettuce.casBasedBuilder(clusterConnection)
                    .expirationAfterWrite(ExpirationAfterWriteStrategy.fixedTimeToLive(KEY_TTL))
                    .build();
        }
        return Bucket4jLettuce.casBasedBuilder((StatefulRedisConnection<String, byte[]>) connection)
                .expirationAfterWrite(ExpirationAfterWriteStrategy.fixedTimeToLive(KEY_TTL))
                .build();
    }

    /** Bean shutdown: close only the dedicated connection; the client belongs to the factory. */
    @Override
    public void close() {
        synchronized (connectLock) {
            closed = true;
            if (connection != null) {
                connection.close();
                connection = null;
            }
            proxyManager = null;
        }
    }
}
