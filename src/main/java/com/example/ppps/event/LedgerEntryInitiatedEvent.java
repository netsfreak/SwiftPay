package com.example.ppps.event;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Event emitted when a ledger entry is initiated (transaction started)
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LedgerEntryInitiatedEvent extends LedgerEventBase {
    private UUID transactionId;
    private UUID walletId;
    private BigDecimal amount;
    private String entryType; // DEBIT or CREDIT
    private String description;

    public LedgerEntryInitiatedEvent(UUID transactionId, UUID walletId, BigDecimal amount,
                                      String entryType, String description, String correlationId) {
        super(transactionId, "LEDGER_ENTRY_INITIATED", correlationId);
        this.transactionId = transactionId;
        this.walletId = walletId;
        this.amount = amount;
        this.entryType = entryType;
        this.description = description;
    }
}
