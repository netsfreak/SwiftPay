package com.example.wallet.model;

import java.math.BigDecimal;
import java.util.UUID;

public class WalletDtos {
    public record WalletBalance(UUID walletId, BigDecimal balance) {}
    public record AmountRequest(BigDecimal amount) {}
}
