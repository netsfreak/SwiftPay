package com.example.ppps.service.event;

import com.example.ppps.entity.EventStore;
import com.example.ppps.event.LedgerEventBase;
import com.example.ppps.repository.EventStoreRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.CompletableFuture;

/**
 * LedgerEventPublisher - Publishes ledger events to Kafka and persists them in EventStore
 * Implements the outbox pattern for reliable event publication
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LedgerEventPublisher {

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final EventStoreRepository eventStoreRepository;
    private final ObjectMapper objectMapper;

    @Value("${app.kafka.topic.ledger.events:ledger.events}")
    private String ledgerEventsTopic;

    /**
     * Publishes a ledger event to Kafka and stores it in the event store
     * Implements transactional outbox pattern to ensure reliability
     */
    @Transactional
    public CompletableFuture<Void> publishLedgerEvent(LedgerEventBase event, String metadata) {
        try {
            // 1. Serialize the event to JSON
            String eventPayload = objectMapper.writeValueAsString(event);

            // 2. Store event in EventStore first (ensure persistence)
            EventStore eventStoreEntry = new EventStore(
                    event.getEventId(),
                    event.getAggregateId(),
                    event.getEventType(),
                    eventPayload,
                    (long) event.getEventVersion(),
                    event.getCorrelationId(),
                    event.getCausationId(),
                    metadata
            );
            eventStoreRepository.save(eventStoreEntry);

            log.debug("Event stored in EventStore: {} - {}", event.getEventType(), event.getEventId());

            // 3. Publish to Kafka
            CompletableFuture<SendResult<String, Object>> kafkaFuture = kafkaTemplate
                    .send(ledgerEventsTopic, event.getAggregateId().toString(), event);

            return kafkaFuture.thenApply(result -> {
                log.info("Ledger event published successfully: {} - Transaction: {} - Event ID: {}",
                        event.getEventType(), event.getAggregateId(), event.getEventId());
                
                // Mark as processed in EventStore
                eventStoreEntry.setStatus("PROCESSED");
                eventStoreRepository.save(eventStoreEntry);
                
                return null;
            }).exceptionally(ex -> {
                log.error("Failed to publish ledger event: {} - Error: {}", 
                        event.getEventType(), ex.getMessage(), ex);
                
                // Mark as failed in EventStore
                eventStoreEntry.setStatus("FAILED");
                eventStoreRepository.save(eventStoreEntry);
                
                throw new RuntimeException("Failed to publish ledger event", ex);
            });

        } catch (Exception e) {
            log.error("Error publishing ledger event: {} - Error: {}", 
                    event.getEventType(), e.getMessage(), e);
            return CompletableFuture.failedFuture(e);
        }
    }

    /**
     * Publish event synchronously (blocking)
     */
    @Transactional
    public void publishLedgerEventSync(LedgerEventBase event, String metadata) {
        try {
            publishLedgerEvent(event, metadata).get();
        } catch (Exception e) {
            log.error("Error during synchronous event publishing: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to publish event synchronously", e);
        }
    }

    /**
     * Replay events from EventStore (useful for rebuilding state)
     */
    public void replayEventsForAggregate(java.util.UUID aggregateId) {
        var events = eventStoreRepository.findEventsByAggregateId(aggregateId);
        log.info("Replaying {} events for aggregate: {}", events.size(), aggregateId);
        
        events.forEach(eventStoreEntry -> {
            try {
                Class<?> eventClass = Class.forName(eventStoreEntry.getEventType());
                Object event = objectMapper.readValue(eventStoreEntry.getEventData(), eventClass);
                
                // Publish replayed event
                kafkaTemplate.send(ledgerEventsTopic + ".replay", 
                        aggregateId.toString(), event);
                
                log.debug("Event replayed: {} - Event ID: {}", 
                        eventStoreEntry.getEventType(), eventStoreEntry.getEventId());
            } catch (Exception e) {
                log.error("Error replaying event: {} - Error: {}", 
                        eventStoreEntry.getEventType(), e.getMessage(), e);
            }
        });
    }
}
