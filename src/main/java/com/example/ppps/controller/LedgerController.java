package com.example.ppps.controller;

import com.example.ppps.entity.LedgerEntry;
import com.example.ppps.enums.EntryType;
import com.example.ppps.service.LedgerQueryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/v1/ledger")
@RequiredArgsConstructor
public class LedgerController {

    private final LedgerQueryService ledgerQueryService;

    /**
     * GET /api/v1/ledger/wallet/{walletId}
     * Returns all ledger entries for a wallet (newest first).
     */
    @GetMapping("/wallet/{walletId}")
    public ResponseEntity<List<LedgerEntry>> getWalletLedger(@PathVariable UUID walletId) {
        log.info("📒 Ledger query — wallet: {}", walletId);
        return ResponseEntity.ok(ledgerQueryService.getEntriesForWallet(walletId));
    }

    /**
     * GET /api/v1/ledger/wallet/{walletId}/paged?page=0&size=20
     * Returns paginated ledger entries for a wallet.
     */
    @GetMapping("/wallet/{walletId}/paged")
    public ResponseEntity<Page<LedgerEntry>> getWalletLedgerPaged(
            @PathVariable UUID walletId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ledgerQueryService.getEntriesForWalletPaged(walletId, page, size));
    }

    /**
     * GET /api/v1/ledger/transaction/{transactionId}
     * Returns all ledger entries for a transaction (full double-entry audit trail).
     */
    @GetMapping("/transaction/{transactionId}")
    public ResponseEntity<List<LedgerEntry>> getTransactionLedger(@PathVariable UUID transactionId) {
        log.info("📒 Ledger query — transaction: {}", transactionId);
        return ResponseEntity.ok(ledgerQueryService.getEntriesForTransaction(transactionId));
    }

    /**
     * GET /api/v1/ledger/wallet/{walletId}/type/{entryType}
     * Returns entries filtered by DEBIT, CREDIT, or FEE_REVENUE.
     */
    @GetMapping("/wallet/{walletId}/type/{entryType}")
    public ResponseEntity<List<LedgerEntry>> getEntriesByType(
            @PathVariable UUID walletId,
            @PathVariable EntryType entryType) {
        return ResponseEntity.ok(ledgerQueryService.getEntriesByType(walletId, entryType));
    }

    /**
     * GET /api/v1/ledger/wallet/{walletId}/event/{eventType}
     * Returns entries filtered by event type string (e.g. TRANSFER_COMPLETED).
     */
    @GetMapping("/wallet/{walletId}/event/{eventType}")
    public ResponseEntity<List<LedgerEntry>> getEntriesByEventType(
            @PathVariable UUID walletId,
            @PathVariable String eventType) {
        return ResponseEntity.ok(ledgerQueryService.getEntriesByEventType(walletId, eventType));
    }

    /**
     * GET /api/v1/ledger/wallet/{walletId}/range?from=...&to=...
     * Returns entries within an ISO-8601 date range.
     */
    @GetMapping("/wallet/{walletId}/range")
    public ResponseEntity<List<LedgerEntry>> getEntriesInRange(
            @PathVariable UUID walletId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        return ResponseEntity.ok(ledgerQueryService.getEntriesInDateRange(walletId, from, to));
    }

    /**
     * GET /api/v1/ledger/correlate/{correlationId}
     * Returns all ledger entries sharing a correlationId (traces a distributed flow).
     */
    @GetMapping("/correlate/{correlationId}")
    public ResponseEntity<List<LedgerEntry>> getByCorrelationId(@PathVariable String correlationId) {
        return ResponseEntity.ok(ledgerQueryService.getEntriesByCorrelationId(correlationId));
    }

    /**
     * GET /api/v1/ledger/wallet/{walletId}/reconcile
     * Returns ledger vs wallet balance reconciliation report.
     */
    @GetMapping("/wallet/{walletId}/reconcile")
    public ResponseEntity<Map<String, Object>> reconcile(@PathVariable UUID walletId) {
        log.info("🔍 Balance reconciliation — wallet: {}", walletId);
        return ResponseEntity.ok(ledgerQueryService.reconcileBalance(walletId));
    }

    /**
     * GET /api/v1/ledger/admin/fees
     * Admin endpoint — returns all FEE_REVENUE ledger entries.
     */
    @GetMapping("/admin/fees")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<LedgerEntry>> getAllFeeEntries() {
        return ResponseEntity.ok(ledgerQueryService.getAllFeeEntries());
    }

    /**
     * GET /api/v1/ledger/admin/fees/wallet/{walletId}
     * Admin endpoint — returns FEE_REVENUE entries for the platform wallet.
     */
    @GetMapping("/admin/fees/wallet/{walletId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<LedgerEntry>> getFeeEntriesForWallet(@PathVariable UUID walletId) {
        return ResponseEntity.ok(ledgerQueryService.getFeeEntriesForWallet(walletId));
    }
}
