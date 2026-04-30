package com.example.payment.service;

import com.example.payment.event.TransactionCompletedEvent;
import com.example.payment.model.PaymentDtos.TransferRequest;
import com.example.payment.model.PaymentDtos.TransferResponse;
import com.example.payment.model.PaymentDtos.WalletAmount;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Service
public class PaymentOrchestratorService {
    private final RestClient restClient;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final String walletServiceBaseUrl;
    private final ConcurrentHashMap<UUID, String> idempotencyCache = new ConcurrentHashMap<>();

    public PaymentOrchestratorService(
            RestClient restClient,
            KafkaTemplate<String, Object> kafkaTemplate,
            @Value("${services.wallet.base-url:http://localhost:9083}") String walletServiceBaseUrl) {
        this.restClient = restClient;
        this.kafkaTemplate = kafkaTemplate;
        this.walletServiceBaseUrl = walletServiceBaseUrl;
    }

    public TransferResponse transfer(TransferRequest request) {
        UUID transactionId = request.transactionId() != null ? 
            request.transactionId() : UUID.randomUUID();

        // Check idempotency - if same transaction already processed, return cached result
        String cachedResult = idempotencyCache.get(transactionId);
        if (cachedResult != null) {
            return new TransferResponse(transactionId, cachedResult);
        }

        String status = "COMPLETED";
        try {
            // Debit from sender wallet (with circuit breaker)
            debitWithRetry(request.senderWalletId(), request.amount());
            
            // Credit to receiver wallet (with circuit breaker)
            creditWithRetry(request.receiverWalletId(), request.amount());
            
            // Publish event asynchronously
            publishTransactionEvent(transactionId, request, "COMPLETED");
            
        } catch (RestClientException | IllegalArgumentException e) {
            status = "FAILED";
            publishTransactionEvent(transactionId, request, "FAILED");
            throw new RuntimeException("Transfer failed: " + e.getMessage(), e);
        }

        // Cache successful result for idempotency
        idempotencyCache.put(transactionId, status);
        
        // Cleanup cache after 5 minutes (prevent memory leak)
        new Thread(() -> {
            try {
                Thread.sleep(300_000); // 5 minutes
                idempotencyCache.remove(transactionId);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }).start();

        return new TransferResponse(transactionId, status);
    }

    @CircuitBreaker(name = "walletService", fallbackMethod = "debitFallback")
    private void debitWithRetry(UUID walletId, java.math.BigDecimal amount) {
        restClient.post()
                .uri(walletServiceBaseUrl + "/internal/wallets/" + walletId + "/debit")
                .body(new WalletAmount(amount))
                .retrieve()
                .toBodilessEntity();
    }

    @CircuitBreaker(name = "walletService", fallbackMethod = "creditFallback")
    private void creditWithRetry(UUID walletId, java.math.BigDecimal amount) {
        restClient.post()
                .uri(walletServiceBaseUrl + "/internal/wallets/" + walletId + "/credit")
                .body(new WalletAmount(amount))
                .retrieve()
                .toBodilessEntity();
    }

    private void debitFallback(UUID walletId, java.math.BigDecimal amount, 
                               Exception e) throws Exception {
        System.err.println("Debit fallback triggered for wallet: " + walletId);
        throw new RuntimeException("Wallet service unavailable, circuit breaker open", e);
    }

    private void creditFallback(UUID walletId, java.math.BigDecimal amount, 
                                Exception e) throws Exception {
        System.err.println("Credit fallback triggered for wallet: " + walletId);
        throw new RuntimeException("Wallet service unavailable, circuit breaker open", e);
    }

    private void publishTransactionEvent(UUID transactionId, TransferRequest request, String status) {
        TransactionCompletedEvent event = new TransactionCompletedEvent(
                transactionId,
                request.senderWalletId(),
                request.receiverWalletId(),
                request.amount(),
                status,
                Instant.now());

        kafkaTemplate.send("transactions.completed", transactionId.toString(), event);
    }
}
