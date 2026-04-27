package com.example.payment.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record TransactionCompletedEvent(
        UUID transactionId,
        UUID senderWalletId,
        UUID receiverWalletId,
        BigDecimal amount,
        String status,
        Instant completedAt
) {
}
