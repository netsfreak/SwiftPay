package com.example.ppps.event;

import lombok.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FeeCollectedEvent {
    private UUID transactionId;
    private UUID sourceWalletId;
    private UUID platformWalletId;
    private BigDecimal feeAmount;
    private String eventType;       // "TRANSFER_FEE", "WITHDRAWAL_FEE", "DEPOSIT_FEE"
    private String correlationId;
    private Instant collectedAt;
}
