package com.example.funding.model;

import java.math.BigDecimal;
import java.util.UUID;

public class FundingDtos {
    public record FundingRequest(UUID walletId, BigDecimal amount, String reference) {}
    public record WalletAmount(BigDecimal amount) {}
    public record FundingResponse(UUID transactionId, String status, String type) {}
}
