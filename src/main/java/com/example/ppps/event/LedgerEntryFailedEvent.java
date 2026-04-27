package com.example.ppps.event;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.UUID;

/**
 * Event emitted when a ledger entry fails to record
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LedgerEntryFailedEvent extends LedgerEventBase {
    private UUID ledgerEntryId;
    private UUID transactionId;
    private String reason;
    private String errorCode;

    public LedgerEntryFailedEvent(UUID transactionId, String reason, String errorCode, String correlationId) {
        super(transactionId, "LEDGER_ENTRY_FAILED", correlationId);
        this.transactionId = transactionId;
        this.reason = reason;
        this.errorCode = errorCode;
    }
}
