package com.example.ppps.service.event;

import com.example.ppps.entity.EventStore;
import com.example.ppps.entity.LedgerEntry;
import com.example.ppps.enums.EntryType;
import com.example.ppps.event.*;
import com.example.ppps.repository.EventStoreRepository;
import com.example.ppps.repository.LedgerEntryRepository;
import com.example.ppps.repository.TransactionRepository;
import com.example.ppps.repository.WalletRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * LedgerEventService - Core service for event-driven ledger operations
 * Handles ledger entry creation, reconciliation, and reversal through events
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LedgerEventService {

    private final LedgerEventPublisher eventPublisher;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final TransactionRepository transactionRepository;
    private final WalletRepository walletRepository;
    private final EventStoreRepository eventStoreRepository;
    private final ObjectMapper objectMapper;

    /**
     * Initiate ledger entries for a transaction (emit event, don't write directly to DB)
     */
    @Transactional
    public void initiateTransactionLedger(UUID transactionId, UUID senderWalletId,
                                         UUID receiverWalletId, BigDecimal amount,
                                         String correlationId) {
        log.info("Initiating transaction ledger for TX: {} - Amount: {}", transactionId, amount);

        // Emit TransactionLedgerInitiated event
        TransactionLedgerInitiatedEvent event = new TransactionLedgerInitiatedEvent(
                transactionId, senderWalletId, receiverWalletId, amount, correlationId);

        String metadata = String.format("{\"source\": \"TransferService\", \"senderWallet\": \"%s\", \"receiverWallet\": \"%s\"}",
                senderWalletId, receiverWalletId);

        eventPublisher.publishLedgerEventSync(event, metadata);
        log.info("Transaction ledger initiation event published for TX: {}", transactionId);
    }

    /**
     * Record ledger entries based on LedgerEntryInitiated event
     * This is called by the event listener after receiving the event
     */
    @Transactional
    public void recordLedgerEntry(UUID transactionId, UUID walletId, BigDecimal amount,
                                  String entryType, String description, String correlationId) {
        log.info("Recording ledger entry - TX: {} - Wallet: {} - Amount: {} - Type: {}",
                transactionId, walletId, amount, entryType);

        try {
            // Create and persist the ledger entry
            LedgerEntry ledgerEntry = new LedgerEntry(
                    UUID.randomUUID(),
                    amount,
                    Instant.now(),
                    EntryType.valueOf(entryType),
                    transactionId,
                    walletId
            );
            LedgerEntry savedEntry = ledgerEntryRepository.save(ledgerEntry);

            // Emit LedgerEntryRecorded event
            LedgerEntryRecordedEvent recordedEvent = new LedgerEntryRecordedEvent(
                    savedEntry.getId(),
                    transactionId,
                    walletId,
                    amount,
                    entryType,
                    String.format("Ledger entry for %s", description),
                    correlationId
            );

            String metadata = String.format("{\"description\": \"%s\", \"source\": \"LedgerEventService\"}",
                    description);

            eventPublisher.publishLedgerEventSync(recordedEvent, metadata);
            log.info("Ledger entry recorded successfully - Entry ID: {} - TX: {}", 
                    savedEntry.getId(), transactionId);

        } catch (Exception e) {
            log.error("Error recording ledger entry for TX: {} - Error: {}", 
                    transactionId, e.getMessage(), e);
            
            // Emit failure event
            LedgerEntryFailedEvent failureEvent = new LedgerEntryFailedEvent(
                    transactionId,
                    e.getMessage(),
                    "LEDGER_ENTRY_CREATION_FAILED",
                    correlationId
            );
            eventPublisher.publishLedgerEventSync(failureEvent, 
                    "{\"error\": \"" + e.getMessage() + "\"}");
            
            throw new RuntimeException("Failed to record ledger entry", e);
        }
    }

    /**
     * Reconcile ledger entries for a transaction (verify debits == credits)
     */
    @Transactional
    public void reconcileTransactionLedger(UUID transactionId, UUID senderWalletId,
                                          UUID receiverWalletId, BigDecimal amount,
                                          String correlationId) {
        log.info("Reconciling ledger entries for TX: {}", transactionId);

        // Get all ledger entries for this transaction
        List<LedgerEntry> entries = ledgerEntryRepository.findByTransactionId(transactionId);

        // Calculate totals
        BigDecimal totalDebits = entries.stream()
                .filter(e -> e.getEntryType() == EntryType.DEBIT)
                .map(LedgerEntry::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalCredits = entries.stream()
                .filter(e -> e.getEntryType() == EntryType.CREDIT)
                .map(LedgerEntry::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        boolean balanced = totalDebits.equals(totalCredits);

        log.info("Ledger reconciliation - TX: {} - Debits: {} - Credits: {} - Balanced: {}",
                transactionId, totalDebits, totalCredits, balanced);

        // Emit reconciliation completed event
        TransactionLedgerReconciliationCompletedEvent reconciliationEvent =
                new TransactionLedgerReconciliationCompletedEvent(
                        transactionId, senderWalletId, receiverWalletId, amount,
                        totalDebits, totalCredits, correlationId);

        String metadata = String.format("{\"entryCount\": %d, \"balanced\": %b}", 
                entries.size(), balanced);

        eventPublisher.publishLedgerEventSync(reconciliationEvent, metadata);
        log.info("Ledger reconciliation event published for TX: {}", transactionId);
    }

    /**
     * Initiate reversal of a ledger entry (for refunds, corrections)
     */
    @Transactional
    public void initiateReversalLedgerEntry(UUID originalLedgerEntryId, UUID transactionId,
                                           UUID walletId, String reason,
                                           String reversalType, String correlationId) {
        log.info("Initiating ledger entry reversal - Original Entry: {} - TX: {} - Reason: {}",
                originalLedgerEntryId, transactionId, reason);

        try {
            // Get original entry to determine reversal amount
            LedgerEntry originalEntry = ledgerEntryRepository.findById(originalLedgerEntryId)
                    .orElseThrow(() -> new RuntimeException("Original ledger entry not found"));

            // Emit reversal initiated event
            LedgerEntryReversalInitiatedEvent reversalEvent = new LedgerEntryReversalInitiatedEvent(
                    originalLedgerEntryId,
                    transactionId,
                    walletId,
                    originalEntry.getAmount(),
                    reason,
                    reversalType,
                    correlationId
            );

            String metadata = String.format("{\"reversalType\": \"%s\", \"reason\": \"%s\"}", 
                    reversalType, reason);

            eventPublisher.publishLedgerEventSync(reversalEvent, metadata);
            log.info("Ledger entry reversal event published - Original Entry: {}", 
                    originalLedgerEntryId);

        } catch (Exception e) {
            log.error("Error initiating reversal for entry: {} - Error: {}",
                    originalLedgerEntryId, e.getMessage(), e);
            throw new RuntimeException("Failed to initiate ledger entry reversal", e);
        }
    }

    /**
     * Get event history for a transaction (audit trail)
     */
    public List<EventStore> getTransactionEventHistory(UUID transactionId) {
        log.debug("Retrieving event history for TX: {}", transactionId);
        return eventStoreRepository.findEventsByAggregateId(transactionId);
    }

    /**
     * Get ledger entries for a transaction
     */
    public List<LedgerEntry> getTransactionLedgerEntries(UUID transactionId) {
        return ledgerEntryRepository.findByTransactionId(transactionId);
    }

    /**
     * Verify ledger balance for a wallet
     */
    public BigDecimal getWalletLedgerBalance(UUID walletId) {
        List<LedgerEntry> entries = ledgerEntryRepository.findByWalletId(walletId);

        BigDecimal balance = BigDecimal.ZERO;
        for (LedgerEntry entry : entries) {
            if (entry.getEntryType() == EntryType.CREDIT) {
                balance = balance.add(entry.getAmount());
            } else if (entry.getEntryType() == EntryType.DEBIT) {
                balance = balance.subtract(entry.getAmount());
            }
        }

        return balance;
    }

    /**
     * Get related events using correlation ID
     */
    public List<EventStore> getCorrelatedEvents(String correlationId) {
        return eventStoreRepository.findEventsByCorrelationId(correlationId);
    }
}
