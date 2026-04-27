package com.example.ppps.event;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Event emitted when all ledger entries for a transaction are successfully recorded and reconciled
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TransactionLedgerReconciliationCompletedEvent extends LedgerEventBase {
    private UUID transactionId;
    private UUID senderWalletId;
    private UUID receiverWalletId;
    private BigDecimal amount;
    private BigDecimal totalDebits;
    private BigDecimal totalCredits;
    private boolean balanced;

    public TransactionLedgerReconciliationCompletedEvent(UUID transactionId, UUID senderWalletId,
                                                        UUID receiverWalletId, BigDecimal amount,
                                                        BigDecimal totalDebits, BigDecimal totalCredits,
                                                        String correlationId) {
        super(transactionId, "TRANSACTION_LEDGER_RECONCILIATION_COMPLETED", correlationId);
        this.transactionId = transactionId;
        this.senderWalletId = senderWalletId;
        this.receiverWalletId = receiverWalletId;
        this.amount = amount;
        this.totalDebits = totalDebits;
        this.totalCredits = totalCredits;
        this.balanced = totalDebits.equals(totalCredits);
    }
}
