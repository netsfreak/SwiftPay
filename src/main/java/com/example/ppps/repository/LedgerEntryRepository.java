package com.example.ppps.repository;

import com.example.ppps.entity.LedgerEntry;
import com.example.ppps.enums.EntryType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface LedgerEntryRepository
    extends JpaRepository<LedgerEntry, UUID>
{
    /** All entries for a wallet, newest first */
    List<LedgerEntry> findByWalletIdOrderByCreatedAtDesc(UUID walletId);

    /** Paginated entries for a wallet */
    Page<LedgerEntry> findByWalletId(UUID walletId, Pageable pageable);

    /** Entries for a specific transaction */
    List<LedgerEntry> findByTransactionId(UUID transactionId);

    /** Entries by type for a wallet */
    List<LedgerEntry> findByWalletIdAndEntryType(
        UUID walletId,
        EntryType entryType
    );

    /** Entries by eventType for a wallet */
    List<LedgerEntry> findByWalletIdAndEventType(
        UUID walletId,
        String eventType
    );

    /** Entries within a date range for a wallet */
    @Query(
        "SELECT l FROM LedgerEntry l WHERE l.walletId = :walletId AND l.createdAt BETWEEN :from AND :to ORDER BY l.createdAt DESC"
    )
    List<LedgerEntry> findByWalletIdAndDateRange(
        @Param("walletId") UUID walletId,
        @Param("from") Instant from,
        @Param("to") Instant to
    );

    /** Sum of credits for a wallet (for balance reconciliation) */
    @Query(
        "SELECT COALESCE(SUM(l.amount), 0) FROM LedgerEntry l WHERE l.walletId = :walletId AND l.entryType = 'CREDIT'"
    )
    BigDecimal sumCreditsByWalletId(@Param("walletId") UUID walletId);

    /** Sum of debits for a wallet (for balance reconciliation) */
    @Query(
        "SELECT COALESCE(SUM(l.amount), 0) FROM LedgerEntry l WHERE l.walletId = :walletId AND l.entryType = 'DEBIT'"
    )
    BigDecimal sumDebitsByWalletId(@Param("walletId") UUID walletId);

    /** All FEE_REVENUE entries (admin use) */
    List<LedgerEntry> findByEntryType(EntryType entryType);

    /** Fee entries by platform wallet */
    List<LedgerEntry> findByWalletIdAndEntryTypeOrderByCreatedAtDesc(
        UUID walletId,
        EntryType entryType
    );

    /** Entries by correlationId (trace a full flow) */
    List<LedgerEntry> findByCorrelationId(String correlationId);
}
