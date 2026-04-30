package com.example.payment.model;

import java.math.BigDecimal;
import java.util.UUID;

public class PaymentDtos {
    public record TransferRequest(
        UUID senderWalletId, 
        UUID receiverWalletId, 
        BigDecimal amount,
        UUID transactionId
    ) {
        public TransferRequest(UUID senderWalletId, UUID receiverWalletId, BigDecimal amount) {
            this(senderWalletId, receiverWalletId, amount, null);
        }
    }
    public record TransferResponse(UUID transactionId, String status) {}
    public record WalletAmount(BigDecimal amount) {}
}
