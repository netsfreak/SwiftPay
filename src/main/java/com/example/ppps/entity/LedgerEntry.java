package com.example.ppps.entity;

import com.example.ppps.enums.EntryType;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.*;
import org.hibernate.annotations.GenericGenerator;

@Entity
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "ledger_entries")
public class LedgerEntry {

    @Id
    @GeneratedValue(generator = "UUID")
    @GenericGenerator(
        name = "UUID",
        strategy = "org.hibernate.id.UUIDGenerator"
    )
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(nullable = false)
    private UUID transactionId;

    @Column(nullable = false)
    private UUID walletId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EntryType entryType;

    @Column(nullable = false, columnDefinition = "Decimal(15,2)")
    private BigDecimal amount;

    /**
     * The fee portion of this entry, if applicable.
     * Null for non-fee entries; populated for FEE_REVENUE entries.
     */
    @Column(columnDefinition = "Decimal(15,2)")
    private BigDecimal feeAmount;

    /**
     * Links this ledger entry back to a distributed flow / correlation.
     * Typically propagated from the X-Correlation-ID request header.
     */
    @Column(length = 64)
    private String correlationId;

    /**
     * The name of the domain event that triggered this write.
     * e.g. "TRANSFER_COMPLETED", "DEPOSIT_COMPLETED", "WITHDRAWAL_COMPLETED",
     *      "ESCROW_COMPLETED", "FEE_COLLECTED"
     */
    @Column(length = 64)
    private String eventType;

    /**
     * A short, system-generated, human-readable description of the entry.
     * e.g. "Debit for outgoing transfer to wallet 3f2a…"
     */
    @Column(length = 255)
    private String description;

    /**
     * Optional free-text narration supplied by the initiator of the transaction.
     */
    @Column(length = 500)
    private String narration;

    /**
     * Timestamp at which this entry was persisted.
     * Auto-set via @PrePersist if not provided explicitly.
     */
    private Instant createdAt;

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------

    @PrePersist
    protected void onPrePersist() {
        if (this.createdAt == null) {
            this.createdAt = Instant.now();
        }
    }

    // -----------------------------------------------------------------------
    // Legacy convenience constructor (kept for backward-compatibility)
    // -----------------------------------------------------------------------

    /**
     * Convenience constructor retained for any existing call-sites that
     * instantiate a LedgerEntry with the original six-field signature.
     * New code should prefer the Lombok-generated @Builder.
     */
    public LedgerEntry(
        UUID id,
        BigDecimal amount,
        Instant createdAt,
        EntryType entryType,
        UUID transactionId,
        UUID walletId
    ) {
        this.id = id;
        this.amount = amount;
        this.createdAt = createdAt;
        this.entryType = entryType;
        this.transactionId = transactionId;
        this.walletId = walletId;
    }
}
