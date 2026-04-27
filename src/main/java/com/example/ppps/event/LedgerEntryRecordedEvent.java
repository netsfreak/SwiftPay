package com.example.ppps.event;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Event emitted when a ledger entry is successfully recorded in the ledger
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LedgerEntryRecordedEvent extends LedgerEventBase {
    private UUID ledgerEntryId;
    private UUID transactionId;
    private UUID walletId;
    private BigDecimal amount;
    private String entryType;
    private String reference;

    public LedgerEntryRecordedEvent(UUID ledgerEntryId, UUID transactionId, UUID walletId,
                                     BigDecimal amount, String entryType, String reference, String correlationId) {
        super(transactionId, "LEDGER_ENTRY_RECORDED", correlationId);
        this.ledgerEntryId = ledgerEntryId;
        this.transactionId = transactionId;
        this.walletId = walletId;
        this.amount = amount;
        this.entryType = entryType;
        this.reference = reference;
    }
}
