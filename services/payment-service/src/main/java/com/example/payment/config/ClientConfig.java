package com.example.payment.config;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.core.registry.EntryAddedEvent;
import io.github.resilience4j.core.registry.EntryRemovedEvent;
import io.github.resilience4j.core.registry.EntryReplacedEvent;
import io.github.resilience4j.core.registry.RegistryEventConsumer;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class ClientConfig {
    
    @Bean
    RestClient restClient() {
        return RestClient.builder()
            .requestFactory(new org.springframework.http.client.SimpleClientHttpRequestFactory() {
                {
                    setConnectTimeout(100);  // 100ms connection timeout
                    setReadTimeout(500);      // 500ms read timeout
                }
            })
            .build();
    }

    @Bean
    public CircuitBreakerRegistry circuitBreakerRegistry() {
        CircuitBreakerConfig walletServiceConfig = CircuitBreakerConfig.custom()
            .failureRateThreshold(50.0f)
            .waitDurationInOpenState(Duration.ofSeconds(30))
            .slowCallRateThreshold(50.0f)
            .slowCallDurationThreshold(Duration.ofMillis(500))
            .automaticTransitionFromOpenToHalfOpenEnabled(true)
            .minimumNumberOfCalls(10)
            .recordExceptions(org.springframework.web.client.RestClientException.class,
                             java.net.SocketTimeoutException.class)
            .build();

        CircuitBreakerRegistry registry = CircuitBreakerRegistry.of(walletServiceConfig);
        
        registry.getEventPublisher()
            .onEntryAdded(event -> System.out.println("CircuitBreaker added: " + event.getAddedEntry().getName()))
            .onEntryRemoved(event -> System.out.println("CircuitBreaker removed: " + event.getRemovedEntry().getName()));

        return registry;
    }

    @Bean
    public CircuitBreaker walletServiceCircuitBreaker(CircuitBreakerRegistry registry) {
        return registry.circuitBreaker("walletService");
    }
}
