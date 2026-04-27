package com.example.ppps.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.GenericGenerator;
import org.hibernate.annotations.Type;

import java.time.Instant;
import java.util.UUID;

/**
 * Event Store - Immutable record of all ledger events
 * Implements Event Sourcing for complete audit trail and event replay capability
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "event_store", indexes = {
        @Index(name = "idx_aggregate_id", columnList = "aggregateId"),
        @Index(name = "idx_event_type", columnList = "eventType"),
        @Index(name = "idx_correlation_id", columnList = "correlationId"),
        @Index(name = "idx_created_at", columnList = "createdAt")
})
public class EventStore {

    @Id
    @GeneratedValue(generator = "UUID")
    @GenericGenerator(name = "UUID", strategy = "org.hibernate.id.UUIDGenerator")
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(nullable = false, updatable = false)
    private UUID eventId;

    @Column(nullable = false, updatable = false)
    private UUID aggregateId; // transactionId or walletId

    @Column(nullable = false, updatable = false)
    private String eventType; // Event class name or event type

    @Column(columnDefinition = "TEXT", nullable = false, updatable = false)
    private String eventData; // JSON-serialized event payload

    @Column(nullable = false, updatable = false)
    private Long eventVersion;

    @Column(nullable = false, updatable = false)
    private String correlationId; // Trace correlated events

    @Column(updatable = false)
    private String causationId; // Cause-effect relationship

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(updatable = false)
    private String metadata; // Additional context (JSON)

    @Column(nullable = false)
    private String status; // RECORDED, PROCESSED, FAILED

    @Version
    @Column(nullable = false)
    private Long version; // Optimistic locking

    public EventStore(UUID eventId, UUID aggregateId, String eventType, String eventData,
                     Long eventVersion, String correlationId, String causationId, String metadata) {
        this.eventId = eventId;
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.eventData = eventData;
        this.eventVersion = eventVersion;
        this.correlationId = correlationId;
        this.causationId = causationId;
        this.createdAt = Instant.now();
        this.metadata = metadata;
        this.status = "RECORDED";
    }
}
