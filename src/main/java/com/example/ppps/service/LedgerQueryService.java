package com.example.ppps.service;

import com.example.ppps.entity.LedgerEntry;
import com.example.ppps.entity.Wallet;
import com.example.ppps.enums.EntryType;
import com.example.ppps.exception.PppsException;
import com.example.ppps.repository.LedgerEntryRepository;
import com.example.ppps.repository.WalletRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class LedgerQueryService {

    private final LedgerEntryRepository ledgerEntryRepository;
    private final WalletRepository walletRepository;

    /** Returns all ledger entries for a wallet, newest first. */
    public List<LedgerEntry> getEntriesForWallet(UUID walletId) {
        validateWalletExists(walletId);
        return ledgerEntryRepository.findByWalletIdOrderByCreatedAtDesc(walletId);
    }

    /** Returns paginated ledger entries for a wallet. */
    public Page<LedgerEntry> getEntriesForWalletPaged(UUID walletId, int page, int size) {
        validateWalletExists(walletId);
        PageRequest pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        return ledgerEntryRepository.findByWalletId(walletId, pageable);
    }

    /** Returns all ledger entries for a specific transaction (audit trail). */
    public List<LedgerEntry> getEntriesForTransaction(UUID transactionId) {
        List<LedgerEntry> entries = ledgerEntryRepository.findByTransactionId(transactionId);
        if (entries.isEmpty()) {
            log.warn("No ledger entries found for transaction: {}", transactionId);
        }
        return entries;
    }

    /** Returns entries for a wallet filtered by entry type. */
    public List<LedgerEntry> getEntriesByType(UUID walletId, EntryType entryType) {
        validateWalletExists(walletId);
        return ledgerEntryRepository.findByWalletIdAndEntryType(walletId, entryType);
    }

    /** Returns entries for a wallet filtered by event type (e.g. "TRANSFER_COMPLETED"). */
    public List<LedgerEntry> getEntriesByEventType(UUID walletId, String eventType) {
        validateWalletExists(walletId);
        return ledgerEntryRepository.findByWalletIdAndEventType(walletId, eventType);
    }

    /** Returns entries for a wallet within a date range. */
    public List<LedgerEntry> getEntriesInDateRange(UUID walletId, Instant from, Instant to) {
        validateWalletExists(walletId);
        if (from.isAfter(to)) {
            throw new PppsException(HttpStatus.BAD_REQUEST, "'from' date must be before 'to' date");
        }
        return ledgerEntryRepository.findByWalletIdAndDateRange(walletId, from, to);
    }

    /** Returns all entries sharing a correlationId (traces a full distributed flow). */
    public List<LedgerEntry> getEntriesByCorrelationId(String correlationId) {
        return ledgerEntryRepository.findByCorrelationId(correlationId);
    }

    /**
     * Reconciles ledger balance vs actual wallet balance.
     * Returns a map with: ledgerCredits, ledgerDebits, ledgerBalance, walletBalance, discrepancy, reconciled
     */
    public Map<String, Object> reconcileBalance(UUID walletId) {
        Wallet wallet = walletRepository.findById(walletId)
                .orElseThrow(() -> new PppsException(HttpStatus.NOT_FOUND, "Wallet not found: " + walletId));

        BigDecimal totalCredits  = ledgerEntryRepository.sumCreditsByWalletId(walletId);
        BigDecimal totalDebits   = ledgerEntryRepository.sumDebitsByWalletId(walletId);
        BigDecimal ledgerBalance = totalCredits.subtract(totalDebits);
        BigDecimal walletBalance = wallet.getBalance();
        BigDecimal discrepancy   = walletBalance.subtract(ledgerBalance);
        boolean reconciled       = discrepancy.compareTo(BigDecimal.ZERO) == 0;

        if (!reconciled) {
            log.warn("⚠️ Balance discrepancy detected for wallet {} — ledger={} wallet={} diff={}",
                    walletId, ledgerBalance, walletBalance, discrepancy);
        }

        return Map.of(
                "walletId",      walletId.toString(),
                "ledgerCredits", totalCredits,
                "ledgerDebits",  totalDebits,
                "ledgerBalance", ledgerBalance,
                "walletBalance", walletBalance,
                "discrepancy",   discrepancy,
                "reconciled",    reconciled
        );
    }

    /** Returns all FEE_REVENUE entries (platform admin). */
    public List<LedgerEntry> getAllFeeEntries() {
        return ledgerEntryRepository.findByEntryType(EntryType.FEE_REVENUE);
    }

    /** Returns FEE_REVENUE entries for the platform wallet (newest first). */
    public List<LedgerEntry> getFeeEntriesForWallet(UUID platformWalletId) {
        return ledgerEntryRepository.findByWalletIdAndEntryTypeOrderByCreatedAtDesc(
                platformWalletId, EntryType.FEE_REVENUE);
    }

    private void validateWalletExists(UUID walletId) {
        if (!walletRepository.existsById(walletId)) {
            throw new PppsException(HttpStatus.NOT_FOUND, "Wallet not found: " + walletId);
        }
    }
}
