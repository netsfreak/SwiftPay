package com.example.wallet.service;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

@Service
public class WalletLedgerService {
    private final Map<UUID, BigDecimal> balances = new ConcurrentHashMap<>();

    public BigDecimal get(UUID walletId) {
        return balances.getOrDefault(walletId, BigDecimal.ZERO);
    }

    public BigDecimal credit(UUID walletId, BigDecimal amount) {
        return balances.merge(walletId, amount, BigDecimal::add);
    }

    public BigDecimal debit(UUID walletId, BigDecimal amount) {
        BigDecimal current = get(walletId);
        if (current.compareTo(amount) < 0) {
            throw new IllegalArgumentException("Insufficient funds");
        }
        BigDecimal updated = current.subtract(amount);
        balances.put(walletId, updated);
        return updated;
    }
}
