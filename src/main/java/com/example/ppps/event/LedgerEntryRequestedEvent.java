package com.example.ppps.event;

import com.example.ppps.enums.EntryType;
import lombok.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LedgerEntryRequestedEvent {
    private UUID transactionId;
    private UUID walletId;
    private EntryType entryType;
    private BigDecimal amount;
    private BigDecimal feeAmount;          // nullable — only set for FEE_REVENUE entries
    private String eventType;              // e.g. "TRANSFER_COMPLETED", "DEPOSIT_COMPLETED"
    private String correlationId;
    private String description;
    private String narration;
    private Instant requestedAt;
}
