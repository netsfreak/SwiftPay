package com.example.funding.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record DepositCompletedEvent(
        UUID transactionId,
        UUID walletId,
        BigDecimal amount,
        String externalReference,
        String status,
        Instant completedAt
) {
}
