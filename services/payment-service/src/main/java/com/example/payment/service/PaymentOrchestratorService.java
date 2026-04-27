package com.example.payment.service;

import com.example.payment.event.TransactionCompletedEvent;
import com.example.payment.model.PaymentDtos.TransferRequest;
import com.example.payment.model.PaymentDtos.TransferResponse;
import com.example.payment.model.PaymentDtos.WalletAmount;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
public class PaymentOrchestratorService {
    private final RestClient restClient;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final String walletServiceBaseUrl;

    public PaymentOrchestratorService(
            RestClient restClient,
            KafkaTemplate<String, Object> kafkaTemplate,
            @Value("${services.wallet.base-url:http://localhost:9083}") String walletServiceBaseUrl) {
        this.restClient = restClient;
        this.kafkaTemplate = kafkaTemplate;
        this.walletServiceBaseUrl = walletServiceBaseUrl;
    }

    public TransferResponse transfer(TransferRequest request) {
        UUID transactionId = UUID.randomUUID();

        restClient.post()
                .uri(walletServiceBaseUrl + "/internal/wallets/" + request.senderWalletId() + "/debit")
                .body(new WalletAmount(request.amount()))
                .retrieve()
                .toBodilessEntity();

        restClient.post()
                .uri(walletServiceBaseUrl + "/internal/wallets/" + request.receiverWalletId() + "/credit")
                .body(new WalletAmount(request.amount()))
                .retrieve()
                .toBodilessEntity();

        TransactionCompletedEvent event = new TransactionCompletedEvent(
                transactionId,
                request.senderWalletId(),
                request.receiverWalletId(),
                request.amount(),
                "COMPLETED",
                Instant.now());

        kafkaTemplate.send("transactions.completed", transactionId.toString(), event);
        return new TransferResponse(transactionId, "COMPLETED");
    }
}
