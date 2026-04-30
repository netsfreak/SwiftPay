package com.example.wallet.service;

import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// ================== ENTITY ==================
@jakarta.persistence.Entity
@jakarta.persistence.Table(name = "wallet_ledger", indexes = {
    @jakarta.persistence.Index(name = "idx_wallet_id", columnList = "wallet_id"),
    @jakarta.persistence.Index(name = "idx_created_at", columnList = "created_at DESC")
})
class WalletLedger {
    @jakarta.persistence.Id
    @jakarta.persistence.GeneratedValue(strategy = jakarta.persistence.GenerationType.AUTO)
    private Long id;

    @jakarta.persistence.Column(nullable = false)
    private UUID walletId;

    @jakarta.persistence.Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal balance;

    @jakarta.persistence.Column(nullable = false, updatable = false)
    @jakarta.persistence.Temporal(jakarta.persistence.TemporalType.TIMESTAMP)
    private java.util.Date createdAt = new java.util.Date();

    @jakarta.persistence.Version
    private Long version;

    WalletLedger() {}
    WalletLedger(UUID walletId, BigDecimal balance) {
        this.walletId = walletId;
        this.balance = balance;
    }

    public UUID getWalletId() { return walletId; }
    public BigDecimal getBalance() { return balance; }
    public void setBalance(BigDecimal balance) { this.balance = balance; }
}

// ================== REPOSITORY ==================
@Repository
interface WalletLedgerRepository extends JpaRepository<WalletLedger, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT w FROM WalletLedger w WHERE w.walletId = ?1")
    WalletLedger findByWalletIdWithLock(UUID walletId);

    WalletLedger findByWalletId(UUID walletId);
}

// ================== SERVICE ==================
@Service
public class WalletLedgerService {
    private final WalletLedgerRepository repository;

    public WalletLedgerService(WalletLedgerRepository repository) {
        this.repository = repository;
    }

    public BigDecimal get(UUID walletId) {
        WalletLedger ledger = repository.findByWalletId(walletId);
        return ledger != null ? ledger.getBalance() : BigDecimal.ZERO;
    }

    @Transactional
    public BigDecimal credit(UUID walletId, BigDecimal amount) {
        WalletLedger ledger = repository.findByWalletIdWithLock(walletId);
        if (ledger == null) {
            ledger = new WalletLedger(walletId, BigDecimal.ZERO);
        }
        BigDecimal newBalance = ledger.getBalance().add(amount);
        ledger.setBalance(newBalance);
        repository.save(ledger);
        return newBalance;
    }

    @Transactional
    public BigDecimal debit(UUID walletId, BigDecimal amount) {
        WalletLedger ledger = repository.findByWalletIdWithLock(walletId);
        if (ledger == null) {
            ledger = new WalletLedger(walletId, BigDecimal.ZERO);
        }
        if (ledger.getBalance().compareTo(amount) < 0) {
            throw new IllegalArgumentException("Insufficient funds");
        }
        BigDecimal newBalance = ledger.getBalance().subtract(amount);
        ledger.setBalance(newBalance);
        repository.save(ledger);
        return newBalance;
    }
}
