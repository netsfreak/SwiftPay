package com.example.ppps.event;

import lombok.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EscrowCancelledEvent {
    private UUID transactionId;
    private UUID senderWalletId;
    private BigDecimal amount;
    private String reason;
    private String correlationId;
    private Instant cancelledAt;
}
