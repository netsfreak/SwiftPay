package com.example.ppps.listener;

import com.example.ppps.event.*;
import com.example.ppps.service.event.LedgerEventService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Service;

/**
 * LedgerEventListener - Consumes ledger events from Kafka and processes them
 * Implements the event-driven saga pattern for ledger operations
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LedgerEventListener {

    private final LedgerEventService ledgerEventService;

    /**
     * Listen for TransactionLedgerInitiated events and create ledger entries
     */
    @KafkaListener(
            topics = "${app.kafka.topic.ledger.events:ledger.events}",
            groupId = "${app.kafka.group.ledger.processor:ledger-processor}",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void handleTransactionLedgerInitiated(
            @Payload TransactionLedgerInitiatedEvent event,
            @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
            @Header(KafkaHeaders.RECEIVED_PARTITION_ID) int partition,
            @Header(KafkaHeaders.OFFSET) long offset) {

        log.info("Received TransactionLedgerInitiated event - TX: {} - Offset: {} - Partition: {}",
                event.getTransactionId(), offset, partition);

        try {
            // Record debit entry for sender
            ledgerEventService.recordLedgerEntry(
                    event.getTransactionId(),
                    event.getSenderWalletId(),
                    event.getAmount(),
                    "DEBIT",
                    "Payment transfer debit",
                    event.getCorrelationId()
            );

            // Record credit entry for receiver
            ledgerEventService.recordLedgerEntry(
                    event.getTransactionId(),
                    event.getReceiverWalletId(),
                    event.getAmount(),
                    "CREDIT",
                    "Payment transfer credit",
                    event.getCorrelationId()
            );

            log.info("Ledger entries created for TX: {}", event.getTransactionId());

        } catch (Exception e) {
            log.error("Error handling TransactionLedgerInitiated event for TX: {} - Error: {}",
                    event.getTransactionId(), e.getMessage(), e);
            // In production, implement retry logic or dead letter queue
            throw new RuntimeException("Failed to process TransactionLedgerInitiated event", e);
        }
    }

    /**
     * Listen for LedgerEntryRecorded events
     */
    @KafkaListener(
            topics = "${app.kafka.topic.ledger.events:ledger.events}",
            groupId = "${app.kafka.group.ledger.auditor:ledger-auditor}"
    )
    public void handleLedgerEntryRecorded(
            @Payload LedgerEntryRecordedEvent event,
            @Header(KafkaHeaders.RECEIVED_TOPIC) String topic) {

        log.info("Received LedgerEntryRecorded event - Entry: {} - TX: {}",
                event.getLedgerEntryId(), event.getTransactionId());

        try {
            // Log for audit purposes
            log.debug("Ledger entry recorded: Amount={}, Type={}, Reference={}",
                    event.getAmount(), event.getEntryType(), event.getReference());
            
            // Could trigger additional processing (notifications, analytics, etc.)
        } catch (Exception e) {
            log.error("Error handling LedgerEntryRecorded event - Error: {}",
                    e.getMessage(), e);
        }
    }

    /**
     * Listen for TransactionLedgerReconciliation events
     */
    @KafkaListener(
            topics = "${app.kafka.topic.ledger.events:ledger.events}",
            groupId = "${app.kafka.group.ledger.reconciler:ledger-reconciler}"
    )
    public void handleTransactionLedgerReconciliation(
            @Payload TransactionLedgerReconciliationCompletedEvent event) {

        log.info("Received TransactionLedgerReconciliationCompleted event - TX: {} - Balanced: {}",
                event.getTransactionId(), event.isBalanced());

        try {
            if (!event.isBalanced()) {
                log.error("CRITICAL: Ledger imbalance detected for TX: {} - Debits: {} - Credits: {}",
                        event.getTransactionId(), event.getTotalDebits(), event.getTotalCredits());
                // Alert operations team
                // TODO: Implement alerting mechanism
            } else {
                log.info("Ledger successfully reconciled for TX: {}", event.getTransactionId());
            }
        } catch (Exception e) {
            log.error("Error handling TransactionLedgerReconciliationCompleted event - Error: {}",
                    e.getMessage(), e);
        }
    }

    /**
     * Listen for LedgerEntryFailed events
     */
    @KafkaListener(
            topics = "${app.kafka.topic.ledger.events:ledger.events}",
            groupId = "${app.kafka.group.ledger.error-handler:ledger-error-handler}"
    )
    public void handleLedgerEntryFailed(
            @Payload LedgerEntryFailedEvent event) {

        log.error("Received LedgerEntryFailed event - TX: {} - Error Code: {} - Reason: {}",
                event.getTransactionId(), event.getErrorCode(), event.getReason());

        try {
            // Handle failure - implement retry, compensation, or manual intervention
            // Could trigger saga compensation
        } catch (Exception e) {
            log.error("Error handling LedgerEntryFailed event - Error: {}",
                    e.getMessage(), e);
        }
    }

    /**
     * Listen for LedgerEntryReversal events
     */
    @KafkaListener(
            topics = "${app.kafka.topic.ledger.events:ledger.events}",
            groupId = "${app.kafka.group.ledger.reversal:ledger-reversal}"
    )
    public void handleLedgerEntryReversal(
            @Payload LedgerEntryReversalInitiatedEvent event) {

        log.info("Received LedgerEntryReversalInitiated event - Original Entry: {} - TX: {} - Type: {}",
                event.getOriginalLedgerEntryId(), event.getTransactionId(), event.getReversalType());

        try {
            // Create reversing entry (opposite of original)
            String reversingEntryType = "DEBIT".equals(event.getEntryType()) ? "CREDIT" : "DEBIT";
            
            ledgerEventService.recordLedgerEntry(
                    event.getTransactionId(),
                    event.getWalletId(),
                    event.getAmount(),
                    reversingEntryType,
                    String.format("Reversal: %s - %s", event.getReversalType(), event.getReason()),
                    event.getCorrelationId()
            );

            log.info("Reversal entry created for original entry: {}", 
                    event.getOriginalLedgerEntryId());

        } catch (Exception e) {
            log.error("Error handling LedgerEntryReversal event - Error: {}",
                    e.getMessage(), e);
        }
    }
}
