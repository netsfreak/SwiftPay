package com.example.gateway.config;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.Refill;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
public class RateLimitingFilter extends AbstractGatewayFilterFactory<RateLimitingFilter.Config> {
    
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();
    
    private final Bucket globalBucket = Bucket.builder()
        .addLimit(Bandwidth.classic(500, Refill.intervally(500, Duration.ofSeconds(1))))
        .build();

    public RateLimitingFilter() {
        super(Config.class);
    }

    @Override
    public GatewayFilter apply(Config config) {
        return (exchange, chain) -> {
            if (!globalBucket.tryConsume(1)) {
                exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
                return exchange.getResponse().writeWith(Mono.just(
                    exchange.getResponse().bufferFactory().wrap("Global rate limit exceeded".getBytes(StandardCharsets.UTF_8))
                ));
            }

            String userId = exchange.getRequest().getHeaders().getFirst("X-User-ID");
            if (userId == null) {
                userId = exchange.getRequest().getRemoteAddress() != null ? 
                    exchange.getRequest().getRemoteAddress().getAddress().getHostAddress() : 
                    "unknown";
            }

            Bucket userBucket = buckets.computeIfAbsent(userId, k -> 
                Bucket.builder()
                    .addLimit(Bandwidth.classic(50, Refill.intervally(50, Duration.ofSeconds(1))))
                    .build()
            );

            if (!userBucket.tryConsume(1)) {
                exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
                return exchange.getResponse().writeWith(Mono.just(
                    exchange.getResponse().bufferFactory().wrap("Per-user rate limit exceeded".getBytes(StandardCharsets.UTF_8))
                ));
            }

            return chain.filter(exchange);
        };
    }

    public static class Config {
    }
}
