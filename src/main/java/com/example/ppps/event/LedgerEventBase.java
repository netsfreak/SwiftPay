package com.example.ppps.event;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

/**
 * Base class for all ledger events in the event-driven architecture
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonTypeInfo(use = JsonTypeInfo.Id.CLASS, property = "@class")
public abstract class LedgerEventBase implements Serializable {
    private static final long serialVersionUID = 1L;

    protected UUID eventId;
    protected UUID aggregateId; // transactionId or walletId
    protected Instant eventTimestamp;
    protected String eventType;
    protected int eventVersion = 1;
    protected String correlationId;
    protected String causationId;

    public LedgerEventBase(UUID aggregateId, String eventType, String correlationId) {
        this.eventId = UUID.randomUUID();
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.eventTimestamp = Instant.now();
        this.correlationId = correlationId;
    }
}
