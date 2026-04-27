package com.example.ppps.event;

import com.example.ppps.config.KafkaTopics;
import com.example.ppps.entity.LedgerEntry;
import com.example.ppps.enums.EntryType;
import com.example.ppps.repository.LedgerEntryRepository;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Event-Driven Ledger Consumer — the SOLE writer of LedgerEntry rows.
 *
 * Design principle:
 *   - Services (TransferService, WithdrawalService, WebhookService) publish
 *     LedgerEntryRequestedEvent / EscrowCompletedEvent / EscrowCancelledEvent
 *     via LedgerEventPublisher after their DB transaction commits.
 *   - This consumer receives those events and persists the LedgerEntry rows.
 *
 * Topic ownership (what writes ledger entries for each flow):
 *
 *   INSTANT P2P TRANSFER  → TransferService publishes LedgerEntryRequestedEvent
 *                           (DEBIT sender, CREDIT receiver, FEE_REVENUE platform)
 *                           onto ledger.entry.requested → onLedgerEntryRequested()
 *
 *   WITHDRAWAL            → WithdrawalService publishes LedgerEntryRequestedEvent
 *                           (DEBIT user, FEE_REVENUE platform)
 *                           onto ledger.entry.requested → onLedgerEntryRequested()
 *
 *   DEPOSIT (webhook)     → WebhookService publishes LedgerEntryRequestedEvent
 *                           (CREDIT user wallet)
 *                           onto ledger.entry.requested → onLedgerEntryRequested()
 *
 *   WITHDRAWAL REFUND     → WebhookService publishes LedgerEntryRequestedEvent
 *                           (CREDIT user wallet on failed withdrawal)
 *                           onto ledger.entry.requested → onLedgerEntryRequested()
 *
 *   ESCROW COMPLETION     → EscrowService publishes EscrowCompletedEvent
 *                           (DEBIT sender principal, CREDIT receiver principal)
 *                           onto escrow.completed → onEscrowCompleted()
 *
 *   ESCROW CANCELLATION   → EscrowService publishes EscrowCancelledEvent
 *                           (CREDIT audit entry — escrow principal was never deducted)
 *                           onto escrow.cancelled → onEscrowCancelled()
 *
 * NOTE: We deliberately do NOT listen on transactions.completed,
 *       deposit.completed, or withdrawal.completed here. Those topics carry
 *       downstream notification / analytics events. Listening on them would
 *       produce duplicate ledger rows on top of the explicit
 *       LedgerEntryRequestedEvents already published by the services.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LedgerEventConsumer {

    private final LedgerEntryRepository ledgerEntryRepository;

    // -------------------------------------------------------------------------
    // Primary handler — all explicit ledger-write requests land here
    // -------------------------------------------------------------------------

    /**
     * Handles explicit ledger-entry requests published by services via
     * LedgerEventPublisher.requestLedgerEntry() or publishFeeCollected().
     *
     * Covers:
     *   - Instant P2P transfer: DEBIT sender, CREDIT receiver, FEE_REVENUE platform
     *   - Withdrawal initiated: DEBIT user, FEE_REVENUE platform
     *   - Deposit webhook success: CREDIT user wallet
     *   - Withdrawal webhook refund: CREDIT user wallet
     *   - Escrow fee (at initiation): DEBIT fee only (principal held)
     */
    @KafkaListener(
        topics = KafkaTopics.LEDGER_ENTRY_REQUESTED,
        groupId = "ledger-service"
    )
    @Transactional
    public void onLedgerEntryRequested(LedgerEntryRequestedEvent event) {
        String correlationId =
            event.getCorrelationId() != null
                ? event.getCorrelationId()
                : "unknown";
        MDC.put("correlationId", correlationId);

        try {
            log.info(
                "📒 [LEDGER] Writing entry — type={} wallet={} amount={} eventType={}",
                event.getEntryType(),
                event.getWalletId(),
                event.getAmount(),
                event.getEventType()
            );

            LedgerEntry entry = LedgerEntry.builder()
                .transactionId(event.getTransactionId())
                .walletId(event.getWalletId())
                .entryType(event.getEntryType())
                .amount(event.getAmount())
                .feeAmount(event.getFeeAmount())
                .eventType(event.getEventType())
                .correlationId(correlationId)
                .description(event.getDescription())
                .narration(event.getNarration())
                .createdAt(Instant.now())
                .build();

            ledgerEntryRepository.save(entry);

            log.info(
                "✅ [LEDGER] Entry saved — id={} type={} wallet={} amount={}",
                entry.getId(),
                entry.getEntryType(),
                entry.getWalletId(),
                entry.getAmount()
            );
        } catch (Exception e) {
            log.error(
                "❌ [LEDGER] Failed to save entry — type={} wallet={} tx={} error={}",
                event.getEntryType(),
                event.getWalletId(),
                event.getTransactionId(),
                e.getMessage(),
                e
            );
            throw e; // re-throw so Kafka retries the message
        } finally {
            MDC.clear();
        }
    }

    // -------------------------------------------------------------------------
    // Escrow handlers
    // -------------------------------------------------------------------------

    /**
     * Handles EscrowCompletedEvent published by EscrowService.completeEscrowTransaction().
     *
     * Writes the principal-transfer ledger entries:
     *   - DEBIT  sender   (principal amount released from escrow)
     *   - CREDIT receiver (principal amount received)
     *
     * The fee entries were already written at escrow initiation time via
     * LedgerEntryRequestedEvent, so they are NOT repeated here.
     */
    @KafkaListener(
        topics = KafkaTopics.ESCROW_COMPLETED,
        groupId = "ledger-service"
    )
    @Transactional
    public void onEscrowCompleted(EscrowCompletedEvent event) {
        String correlationId =
            event.getCorrelationId() != null
                ? event.getCorrelationId()
                : event.getTransactionId().toString();
        MDC.put("correlationId", correlationId);

        try {
            log.info(
                "📒 [LEDGER] EscrowCompleted — tx={} sender={} receiver={} amount={}",
                event.getTransactionId(),
                event.getSenderWalletId(),
                event.getReceiverWalletId(),
                event.getAmount()
            );

            // DEBIT sender — principal released
            saveLedgerEntry(
                LedgerEntry.builder()
                    .transactionId(event.getTransactionId())
                    .walletId(event.getSenderWalletId())
                    .entryType(EntryType.DEBIT)
                    .amount(event.getAmount())
                    .correlationId(correlationId)
                    .eventType("ESCROW_COMPLETED")
                    .description(
                        "Escrow principal released — sent to wallet " +
                            event.getReceiverWalletId()
                    )
                    .createdAt(Instant.now())
                    .build()
            );

            // CREDIT receiver — principal received
            saveLedgerEntry(
                LedgerEntry.builder()
                    .transactionId(event.getTransactionId())
                    .walletId(event.getReceiverWalletId())
                    .entryType(EntryType.CREDIT)
                    .amount(event.getAmount())
                    .correlationId(correlationId)
                    .eventType("ESCROW_COMPLETED")
                    .description(
                        "Escrow principal received from wallet " +
                            event.getSenderWalletId()
                    )
                    .createdAt(Instant.now())
                    .build()
            );
        } finally {
            MDC.clear();
        }
    }

    /**
     * Handles EscrowCancelledEvent published by EscrowService.cancelEscrowTransaction().
     *
     * Writes an audit-only CREDIT entry for the sender wallet.
     * Because the principal was never deducted from the sender during escrow
     * initiation (only the fee was), this entry serves as a bookkeeping record
     * confirming the cancellation — no real balance movement is needed.
     */
    @KafkaListener(
        topics = KafkaTopics.ESCROW_CANCELLED,
        groupId = "ledger-service"
    )
    @Transactional
    public void onEscrowCancelled(EscrowCancelledEvent event) {
        String correlationId =
            event.getCorrelationId() != null
                ? event.getCorrelationId()
                : event.getTransactionId().toString();
        MDC.put("correlationId", correlationId);

        try {
            log.info(
                "📒 [LEDGER] EscrowCancelled — tx={} sender={} amount={} reason={}",
                event.getTransactionId(),
                event.getSenderWalletId(),
                event.getAmount(),
                event.getReason()
            );

            saveLedgerEntry(
                LedgerEntry.builder()
                    .transactionId(event.getTransactionId())
                    .walletId(event.getSenderWalletId())
                    .entryType(EntryType.CREDIT)
                    .amount(event.getAmount())
                    .correlationId(correlationId)
                    .eventType("ESCROW_CANCELLED")
                    .description("Escrow cancelled — " + event.getReason())
                    .createdAt(Instant.now())
                    .build()
            );
        } finally {
            MDC.clear();
        }
    }

    // -------------------------------------------------------------------------
    // Private helper
    // -------------------------------------------------------------------------

    private void saveLedgerEntry(LedgerEntry entry) {
        try {
            ledgerEntryRepository.save(entry);
            log.debug(
                "✅ [LEDGER] Saved — type={} wallet={} amount={}",
                entry.getEntryType(),
                entry.getWalletId(),
                entry.getAmount()
            );
        } catch (Exception e) {
            log.error(
                "❌ [LEDGER] Save failed — type={} wallet={} tx={} error={}",
                entry.getEntryType(),
                entry.getWalletId(),
                entry.getTransactionId(),
                e.getMessage(),
                e
            );
            throw e; // re-throw so Kafka retries
        }
    }
}
