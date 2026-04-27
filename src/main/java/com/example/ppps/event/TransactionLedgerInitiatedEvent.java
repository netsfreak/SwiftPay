package com.example.ppps.event;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Event emitted when a transaction is initiated and ledger entries begin
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TransactionLedgerInitiatedEvent extends LedgerEventBase {
    private UUID transactionId;
    private UUID senderWalletId;
    private UUID receiverWalletId;
    private BigDecimal amount;
    private String status; // INITIATED, PROCESSING, etc.

    public TransactionLedgerInitiatedEvent(UUID transactionId, UUID senderWalletId, 
                                           UUID receiverWalletId, BigDecimal amount, 
                                           String correlationId) {
        super(transactionId, "TRANSACTION_LEDGER_INITIATED", correlationId);
        this.transactionId = transactionId;
        this.senderWalletId = senderWalletId;
        this.receiverWalletId = receiverWalletId;
        this.amount = amount;
        this.status = "INITIATED";
    }
}
