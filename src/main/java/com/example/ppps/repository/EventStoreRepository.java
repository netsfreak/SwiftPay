package com.example.ppps.repository;

import com.example.ppps.entity.EventStore;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Repository for Event Store - enables querying and persisting domain events
 */
@Repository
public interface EventStoreRepository extends JpaRepository<EventStore, UUID> {

    /**
     * Find all events for a specific aggregate (transaction/wallet)
     */
    @Query("SELECT e FROM EventStore e WHERE e.aggregateId = :aggregateId ORDER BY e.createdAt ASC")
    List<EventStore> findEventsByAggregateId(@Param("aggregateId") UUID aggregateId);

    /**
     * Find all events for a specific aggregate with pagination
     */
    @Query("SELECT e FROM EventStore e WHERE e.aggregateId = :aggregateId ORDER BY e.createdAt ASC")
    Page<EventStore> findEventsByAggregateId(@Param("aggregateId") UUID aggregateId, Pageable pageable);

    /**
     * Find events by type
     */
    @Query("SELECT e FROM EventStore e WHERE e.eventType = :eventType ORDER BY e.createdAt DESC")
    Page<EventStore> findEventsByType(@Param("eventType") String eventType, Pageable pageable);

    /**
     * Find events by correlation ID (for tracing related events)
     */
    @Query("SELECT e FROM EventStore e WHERE e.correlationId = :correlationId ORDER BY e.createdAt ASC")
    List<EventStore> findEventsByCorrelationId(@Param("correlationId") String correlationId);

    /**
     * Find unprocessed events
     */
    @Query("SELECT e FROM EventStore e WHERE e.status = 'RECORDED' ORDER BY e.createdAt ASC")
    Page<EventStore> findUnprocessedEvents(Pageable pageable);

    /**
     * Count events by type
     */
    long countByEventType(String eventType);

    /**
     * Find events within a time range
     */
    @Query("SELECT e FROM EventStore e WHERE e.createdAt BETWEEN :startTime AND :endTime ORDER BY e.createdAt ASC")
    Page<EventStore> findEventsByTimeRange(@Param("startTime") Instant startTime, 
                                           @Param("endTime") Instant endTime, 
                                           Pageable pageable);
}
