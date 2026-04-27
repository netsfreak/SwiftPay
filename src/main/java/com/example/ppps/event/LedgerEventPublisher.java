package com.example.ppps.event;

import com.example.ppps.config.KafkaTopics;
import com.example.ppps.enums.EntryType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class LedgerEventPublisher {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    /**
     * Schedules a LedgerEntryRequestedEvent to be published after the current DB transaction commits.
     * This guarantees ledger entries are only written when the business transaction succeeded.
     */
    public void requestLedgerEntry(
            UUID transactionId,
            UUID walletId,
            EntryType entryType,
            BigDecimal amount,
            BigDecimal feeAmount,
            String eventType,
            String correlationId,
            String description,
            String narration) {

        LedgerEntryRequestedEvent event = LedgerEntryRequestedEvent.builder()
                .transactionId(transactionId)
                .walletId(walletId)
                .entryType(entryType)
                .amount(amount)
                .feeAmount(feeAmount)
                .eventType(eventType)
                .correlationId(correlationId)
                .description(description)
                .narration(narration)
                .requestedAt(Instant.now())
                .build();

        publishAfterCommit(KafkaTopics.LEDGER_ENTRY_REQUESTED, transactionId.toString(), event);
    }

    /** Publishes an EscrowCompletedEvent after DB commit. */
    public void publishEscrowCompleted(
            UUID transactionId,
            UUID senderWalletId,
            UUID receiverWalletId,
            BigDecimal amount,
            String correlationId) {

        EscrowCompletedEvent event = EscrowCompletedEvent.builder()
                .transactionId(transactionId)
                .senderWalletId(senderWalletId)
                .receiverWalletId(receiverWalletId)
                .amount(amount)
                .correlationId(correlationId)
                .completedAt(Instant.now())
                .build();

        publishAfterCommit(KafkaTopics.ESCROW_COMPLETED, transactionId.toString(), event);
    }

    /** Publishes an EscrowCancelledEvent after DB commit. */
    public void publishEscrowCancelled(
            UUID transactionId,
            UUID senderWalletId,
            BigDecimal amount,
            String reason,
            String correlationId) {

        EscrowCancelledEvent event = EscrowCancelledEvent.builder()
                .transactionId(transactionId)
                .senderWalletId(senderWalletId)
                .amount(amount)
                .reason(reason)
                .correlationId(correlationId)
                .cancelledAt(Instant.now())
                .build();

        publishAfterCommit(KafkaTopics.ESCROW_CANCELLED, transactionId.toString(), event);
    }

    /** Publishes a FeeCollectedEvent after DB commit. */
    public void publishFeeCollected(
            UUID transactionId,
            UUID sourceWalletId,
            UUID platformWalletId,
            BigDecimal feeAmount,
            String feeEventType,
            String correlationId) {

        FeeCollectedEvent event = FeeCollectedEvent.builder()
                .transactionId(transactionId)
                .sourceWalletId(sourceWalletId)
                .platformWalletId(platformWalletId)
                .feeAmount(feeAmount)
                .eventType(feeEventType)
                .correlationId(correlationId)
                .collectedAt(Instant.now())
                .build();

        publishAfterCommit(KafkaTopics.LEDGER_ENTRY_REQUESTED, transactionId.toString(), event);
    }

    /**
     * Core helper: registers a TransactionSynchronization so the Kafka message is only
     * sent AFTER the surrounding DB transaction commits successfully.
     */
    private void publishAfterCommit(String topic, String key, Object payload) {
        String correlationId = MDC.get("correlationId");

        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    doPublish(topic, key, payload, correlationId);
                }

                @Override
                public void afterCompletion(int status) {
                    if (status == STATUS_ROLLED_BACK) {
                        log.warn("[{}] ⚠️ DB transaction rolled back — Kafka event NOT published to topic: {}", correlationId, topic);
                    }
                }
            });
        } else {
            // No active transaction — publish immediately (e.g. in @Scheduled methods)
            doPublish(topic, key, payload, correlationId);
        }
    }

    private void doPublish(String topic, String key, Object payload, String correlationId) {
        kafkaTemplate.send(topic, key, payload)
                .whenComplete((result, ex) -> {
                    if (ex == null) {
                        log.info("[{}] ✅ Ledger event published → topic={} partition={} offset={}",
                                correlationId, topic,
                                result.getRecordMetadata().partition(),
                                result.getRecordMetadata().offset());
                    } else {
                        log.error("[{}] ❌ Failed to publish ledger event → topic={} key={} error={}",
                                correlationId, topic, key, ex.getMessage(), ex);
                    }
                });
    }
}
