package com.example.funding.service;

import com.example.funding.event.DepositCompletedEvent;
import com.example.funding.event.WithdrawalCompletedEvent;
import com.example.funding.model.FundingDtos.FundingRequest;
import com.example.funding.model.FundingDtos.FundingResponse;
import com.example.funding.model.FundingDtos.WalletAmount;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
public class FundingOrchestratorService {
    private final RestClient restClient;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final String walletServiceBaseUrl;

    public FundingOrchestratorService(
            RestClient restClient,
            KafkaTemplate<String, Object> kafkaTemplate,
            @Value("${services.wallet.base-url:http://localhost:9083}") String walletServiceBaseUrl) {
        this.restClient = restClient;
        this.kafkaTemplate = kafkaTemplate;
        this.walletServiceBaseUrl = walletServiceBaseUrl;
    }

    public FundingResponse deposit(FundingRequest request) {
        UUID transactionId = UUID.randomUUID();
        restClient.post()
                .uri(walletServiceBaseUrl + "/internal/wallets/" + request.walletId() + "/credit")
                .body(new WalletAmount(request.amount()))
                .retrieve()
                .toBodilessEntity();

        DepositCompletedEvent event = new DepositCompletedEvent(
                transactionId, request.walletId(), request.amount(), request.reference(), "COMPLETED", Instant.now());
        kafkaTemplate.send("deposit.completed", transactionId.toString(), event);
        return new FundingResponse(transactionId, "COMPLETED", "DEPOSIT");
    }

    public FundingResponse withdraw(FundingRequest request) {
        UUID transactionId = UUID.randomUUID();
        restClient.post()
                .uri(walletServiceBaseUrl + "/internal/wallets/" + request.walletId() + "/debit")
                .body(new WalletAmount(request.amount()))
                .retrieve()
                .toBodilessEntity();

        WithdrawalCompletedEvent event = new WithdrawalCompletedEvent(
                transactionId, request.walletId(), request.amount(), "Bank", "N/A", "COMPLETED", Instant.now());
        kafkaTemplate.send("withdrawal.completed", transactionId.toString(), event);
        return new FundingResponse(transactionId, "COMPLETED", "WITHDRAWAL");
    }
}
