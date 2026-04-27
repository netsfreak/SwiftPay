package com.example.ppps.event;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Event emitted when a ledger entry is reversed (for refunds, cancellations, etc.)
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LedgerEntryReversalInitiatedEvent extends LedgerEventBase {
    private UUID originalLedgerEntryId;
    private UUID reversalLedgerEntryId;
    private UUID transactionId;
    private UUID walletId;
    private BigDecimal amount;
    private String reason;
    private String reversalType; // REFUND, CANCELLATION, CORRECTION

    public LedgerEntryReversalInitiatedEvent(UUID originalLedgerEntryId, UUID transactionId, 
                                            UUID walletId, BigDecimal amount, String reason,
                                            String reversalType, String correlationId) {
        super(transactionId, "LEDGER_ENTRY_REVERSAL_INITIATED", correlationId);
        this.originalLedgerEntryId = originalLedgerEntryId;
        this.reversalLedgerEntryId = UUID.randomUUID();
        this.transactionId = transactionId;
        this.walletId = walletId;
        this.amount = amount;
        this.reason = reason;
        this.reversalType = reversalType;
    }
}
