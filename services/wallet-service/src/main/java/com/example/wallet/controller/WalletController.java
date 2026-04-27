package com.example.wallet.controller;

import com.example.wallet.model.WalletDtos.AmountRequest;
import com.example.wallet.model.WalletDtos.WalletBalance;
import com.example.wallet.service.WalletLedgerService;
import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class WalletController {
    private final WalletLedgerService walletLedgerService;

    public WalletController(WalletLedgerService walletLedgerService) {
        this.walletLedgerService = walletLedgerService;
    }

    @GetMapping("/api/v1/wallets/{walletId}/balance")
    public ResponseEntity<WalletBalance> getBalance(@PathVariable UUID walletId) {
        return ResponseEntity.ok(new WalletBalance(walletId, walletLedgerService.get(walletId)));
    }

    @PostMapping("/internal/wallets/{walletId}/credit")
    public ResponseEntity<WalletBalance> credit(@PathVariable UUID walletId, @RequestBody AmountRequest request) {
        BigDecimal updated = walletLedgerService.credit(walletId, request.amount());
        return ResponseEntity.ok(new WalletBalance(walletId, updated));
    }

    @PostMapping("/internal/wallets/{walletId}/debit")
    public ResponseEntity<WalletBalance> debit(@PathVariable UUID walletId, @RequestBody AmountRequest request) {
        BigDecimal updated = walletLedgerService.debit(walletId, request.amount());
        return ResponseEntity.ok(new WalletBalance(walletId, updated));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> handleValidation(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(ex.getMessage());
    }
}
