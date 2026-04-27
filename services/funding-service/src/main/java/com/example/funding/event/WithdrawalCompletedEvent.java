package com.example.funding.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record WithdrawalCompletedEvent(
        UUID transactionId,
        UUID senderWalletId,
        BigDecimal amount,
        String bankName,
        String accountNumber,
        String status,
        Instant completedAt
) {
}
