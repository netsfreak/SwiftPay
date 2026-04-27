package com.example.ppps.service;

import com.example.ppps.dto.GatewayRequest;
import com.example.ppps.dto.GatewayResponse;
import com.example.ppps.entity.*;
import com.example.ppps.enums.EntryType;
import com.example.ppps.enums.TransactionStatus;
import com.example.ppps.event.LedgerEventPublisher;
import com.example.ppps.event.TransactionCompletedEvent;
import com.example.ppps.exception.*;
import com.example.ppps.repository.*;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class TransferService {

    private static final Logger logger = LoggerFactory.getLogger(
        TransferService.class
    );

    private final WalletRepository walletRepository;
    private final TransactionRepository transactionRepository;
    private final UserRepository userRepository;
    private final EntityManager entityManager;
    private final Timer transferTimer;
    private final PasswordEncoder passwordEncoder;
    private final FeeService feeService;
    private final GatewayService gatewayService;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final UUID platformWalletId;
    private final EscrowService escrowService;
    private final LedgerEventPublisher ledgerEventPublisher;

    @Value("${app.kafka.topic.transactions.completed:transactions.completed}")
    private String transactionCompletedTopic;

    public TransferService(
        WalletRepository walletRepository,
        TransactionRepository transactionRepository,
        UserRepository userRepository,
        EntityManager entityManager,
        MeterRegistry meterRegistry,
        PasswordEncoder passwordEncoder,
        FeeService feeService,
        GatewayService gatewayService,
        KafkaTemplate<String, Object> kafkaTemplate,
        EscrowService escrowService,
        LedgerEventPublisher ledgerEventPublisher,
        @Value("${app.platform-wallet-id}") String platformWalletIdStr
    ) {
        this.walletRepository = walletRepository;
        this.transactionRepository = transactionRepository;
        this.userRepository = userRepository;
        this.entityManager = entityManager;
        this.passwordEncoder = passwordEncoder;
        this.feeService = feeService;
        this.gatewayService = gatewayService;
        this.kafkaTemplate = kafkaTemplate;
        this.escrowService = escrowService;
        this.ledgerEventPublisher = ledgerEventPublisher;

        this.transferTimer = Timer.builder("ppps.transfer.duration")
            .description("Time taken to process a P2P transfer")
            .register(meterRegistry);

        this.platformWalletId = (platformWalletIdStr == null ||
            platformWalletIdStr.isBlank())
            ? null
            : UUID.fromString(platformWalletIdStr);
    }

    @Transactional
    public void executeP2PTransfer(
        String receiverPhoneNumber,
        BigDecimal amount,
        String securePin,
        String narration
    ) {
        transferTimer.record(() -> {
            String correlationId = UUID.randomUUID().toString();
            MDC.put("correlationId", correlationId);

            try {
                logger.info(
                    "🚀 Starting P2P transfer - Receiver: {}, Amount: {}",
                    receiverPhoneNumber,
                    amount
                );

                // Authentication & User Validation
                var authentication =
                    SecurityContextHolder.getContext().getAuthentication();
                if (authentication == null) {
                    throw new PppsException(
                        HttpStatus.UNAUTHORIZED,
                        "No authentication context"
                    );
                }
                String userId = authentication.getPrincipal().toString();
                logger.debug("Authenticated user ID: {}", userId);

                User senderUser = userRepository
                    .findById(userId)
                    .orElseThrow(() ->
                        new PppsException(
                            HttpStatus.NOT_FOUND,
                            "Sender user not found"
                        )
                    );

                User receiverUser = userRepository
                    .findByPhoneNumber(receiverPhoneNumber)
                    .orElseThrow(() ->
                        new PppsException(
                            HttpStatus.NOT_FOUND,
                            "Receiver with phone number " +
                                receiverPhoneNumber +
                                " not found"
                        )
                    );
                logger.info(
                    "✅ Sender: {} | Receiver: {}",
                    senderUser.getPhoneNumber(),
                    receiverUser.getPhoneNumber()
                );

                // Wallet Validation
                Wallet senderWallet = senderUser.getWallet();
                Wallet receiverWallet = receiverUser.getWallet();

                if (senderWallet == null || receiverWallet == null) {
                    throw new PppsException(
                        HttpStatus.BAD_REQUEST,
                        "Both users must have wallets"
                    );
                }

                UUID senderWalletId = senderWallet.getId();
                UUID receiverWalletId = receiverWallet.getId();

                if (senderWalletId.equals(receiverWalletId)) {
                    throw new PppsException(
                        HttpStatus.BAD_REQUEST,
                        "Cannot transfer to yourself"
                    );
                }
                logger.debug(
                    "Sender Wallet ID: {} | Receiver Wallet ID: {}",
                    senderWalletId,
                    receiverWalletId
                );

                // PIN Verification
                if (securePin == null || securePin.trim().isEmpty()) {
                    throw new PppsException(
                        HttpStatus.BAD_REQUEST,
                        "PIN is required"
                    );
                }

                if (!verifyPin(securePin, senderUser.getHashedPin())) {
                    logger.warn("⚠️ Invalid PIN attempt for user: {}", userId);
                    throw new PppsException(
                        HttpStatus.UNAUTHORIZED,
                        "Invalid PIN"
                    );
                }
                logger.debug("✅ PIN verified successfully");

                // Amount Validation & Fee Calculation
                if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
                    throw new PppsException(
                        HttpStatus.BAD_REQUEST,
                        "Amount must be greater than zero"
                    );
                }

                BigDecimal fee = feeService.calculateFee(amount);
                if (fee == null) fee = BigDecimal.ZERO;
                BigDecimal totalDebit = amount.add(fee);
                logger.info(
                    "💰 Amount: {} | Fee: {} | Total Debit: {}",
                    amount,
                    fee,
                    totalDebit
                );

                // Lock Wallets (Pessimistic Locking)
                logger.debug("🔒 Acquiring locks on wallets...");

                Wallet senderWalletLocked = walletRepository.findByIdWithLock(
                    senderWalletId
                );
                Wallet receiverWalletLocked = walletRepository.findByIdWithLock(
                    receiverWalletId
                );

                if (
                    senderWalletLocked == null || receiverWalletLocked == null
                ) {
                    throw new WalletNotFoundException("Wallet lock failed");
                }

                Wallet platformWallet = null;
                if (fee.compareTo(BigDecimal.ZERO) > 0) {
                    if (platformWalletId == null) {
                        throw new PppsException(
                            HttpStatus.INTERNAL_SERVER_ERROR,
                            "Platform wallet not configured"
                        );
                    }
                    platformWallet = walletRepository.findByIdWithLock(
                        platformWalletId
                    );
                    if (platformWallet == null) {
                        throw new PppsException(
                            HttpStatus.INTERNAL_SERVER_ERROR,
                            "Platform wallet not found"
                        );
                    }
                }
                logger.debug("✅ Wallets locked successfully");

                // Balance Check
                if (senderWalletLocked.getBalance().compareTo(totalDebit) < 0) {
                    logger.warn(
                        "⚠️ Insufficient funds - Required: {}, Available: {}",
                        totalDebit,
                        senderWalletLocked.getBalance()
                    );
                    throw new InsufficientFundsException(
                        String.format(
                            "Insufficient funds. Required: %.2f, Available: %.2f",
                            totalDebit,
                            senderWalletLocked.getBalance()
                        )
                    );
                }

                // Escrow Check & Balance Updates
                boolean requiresEscrow = escrowService.requiresEscrow(amount);

                BigDecimal senderOldBalance = senderWalletLocked.getBalance();
                BigDecimal receiverOldBalance =
                    receiverWalletLocked.getBalance();

                if (requiresEscrow) {
                    // For escrow: Only deduct fee immediately, hold principal amount
                    logger.info(
                        "🔄 Transfer requires escrow - Amount: {}",
                        amount
                    );

                    // Deduct only the fee immediately (principal remains in sender's wallet)
                    senderWalletLocked.setBalance(
                        senderWalletLocked.getBalance().subtract(fee)
                    );

                    logger.info(
                        "💰 Escrow setup - Fee deducted: {}, Principal held: {}",
                        fee,
                        amount
                    );
                } else {
                    // Instant transfer: Deduct full amount immediately
                    senderWalletLocked.setBalance(
                        senderWalletLocked.getBalance().subtract(totalDebit)
                    );
                    receiverWalletLocked.setBalance(
                        receiverWalletLocked.getBalance().add(amount)
                    );
                    logger.info(
                        "⚡ Instant transfer - Total debit: {}, Receiver credit: {}",
                        totalDebit,
                        amount
                    );
                }

                // Platform fee is always deducted immediately -- for both escrow and instant
                if (
                    platformWallet != null && fee.compareTo(BigDecimal.ZERO) > 0
                ) {
                    platformWallet.setBalance(
                        platformWallet.getBalance().add(fee)
                    );
                    logger.info("🏦 Platform fee collected: {}", fee);
                }

                // Save wallet updates
                walletRepository.saveAndFlush(senderWalletLocked);
                if (!requiresEscrow) {
                    // Only update receiver wallet for instant transfers
                    walletRepository.saveAndFlush(receiverWalletLocked);
                }
                if (platformWallet != null) {
                    walletRepository.saveAndFlush(platformWallet);
                }

                logger.info(
                    "💸 Balances updated - Sender: {} → {} | Receiver: {} → {}",
                    senderOldBalance,
                    senderWalletLocked.getBalance(),
                    receiverOldBalance,
                    requiresEscrow
                        ? receiverOldBalance
                        : receiverWalletLocked.getBalance()
                );

                // Create Transaction Record with Escrow Status
                Transaction transaction = new Transaction();
                transaction.setSenderWalletId(senderWalletId);
                transaction.setReceiverWalletId(receiverWalletId);
                transaction.setAmount(amount);
                transaction.setInitiatedAt(Instant.now());

                // Set status based on escrow
                if (requiresEscrow) {
                    transaction.setStatus(TransactionStatus.PENDING);
                    logger.info("⏳ Transaction set to PENDING (escrow)");

                    // Save transaction first, then create escrow hold
                    transaction = transactionRepository.saveAndFlush(
                        transaction
                    );
                    escrowService.createEscrowHold(transaction.getId(), amount);
                } else {
                    transaction.setStatus(TransactionStatus.SUCCESS);
                    logger.info("✅ Transaction set to SUCCESS (instant)");
                    transaction = transactionRepository.saveAndFlush(
                        transaction
                    );
                }
                logger.info(
                    "📝 Transaction record created - ID: {}",
                    transaction.getId()
                );

                // Publish Ledger Events (Double-Entry Bookkeeping via event-driven ledger)
                if (requiresEscrow) {
                    // For escrow: Only record fee entry now; principal entries are created by
                    // EscrowService.completeEscrowTransaction() via publishEscrowCompleted().
                    ledgerEventPublisher.requestLedgerEntry(
                        transaction.getId(),
                        senderWalletId,
                        EntryType.DEBIT,
                        fee, // amount = fee only (principal is still held in sender wallet)
                        fee, // feeAmount
                        "TRANSFER_FEE",
                        correlationId,
                        "Escrow fee deducted — principal pending release",
                        narration
                    );

                    if (fee.compareTo(BigDecimal.ZERO) > 0) {
                        ledgerEventPublisher.publishFeeCollected(
                            transaction.getId(),
                            senderWalletId,
                            platformWalletId,
                            fee,
                            "TRANSFER_FEE",
                            correlationId
                        );
                        logger.debug(
                            "💳 Escrow fee ledger event published - Amount: {}",
                            fee
                        );
                    }

                    logger.info(
                        "📘 Escrow ledger events published - Fee recorded, principal pending"
                    );
                } else {
                    // For instant transfers: Record all entries immediately via events
                    ledgerEventPublisher.requestLedgerEntry(
                        transaction.getId(),
                        senderWalletId,
                        EntryType.DEBIT,
                        totalDebit,
                        fee,
                        "TRANSFER_COMPLETED",
                        correlationId,
                        "P2P transfer sent to wallet " + receiverWalletId,
                        narration
                    );

                    ledgerEventPublisher.requestLedgerEntry(
                        transaction.getId(),
                        receiverWalletId,
                        EntryType.CREDIT,
                        amount,
                        BigDecimal.ZERO,
                        "TRANSFER_COMPLETED",
                        correlationId,
                        "P2P transfer received from wallet " + senderWalletId,
                        narration
                    );

                    if (fee.compareTo(BigDecimal.ZERO) > 0) {
                        ledgerEventPublisher.publishFeeCollected(
                            transaction.getId(),
                            senderWalletId,
                            platformWalletId,
                            fee,
                            "TRANSFER_FEE",
                            correlationId
                        );
                        logger.debug(
                            "💳 Instant transfer fee ledger event published - Amount: {}",
                            fee
                        );
                    }

                    logger.info("📘 Instant transfer ledger events published");
                }

                entityManager.flush();
                logger.debug("✅ Ledger events queued (afterCommit)");

                // Gateway Payment Processing
                logger.info("🌐 Calling payment gateway...");

                GatewayRequest gatewayRequest = GatewayRequest.builder()
                    .transactionId(transaction.getId())
                    .amount(amount)
                    .currency("NGN")
                    .metadata(
                        Map.of(
                            "senderWallet",
                            senderWalletId.toString(),
                            "receiverWallet",
                            receiverWalletId.toString(),
                            "narration",
                            narration != null ? narration : "",
                            "correlationId",
                            correlationId
                        )
                    )
                    .build();

                GatewayResponse gatewayResponse = gatewayService.processPayment(
                    gatewayRequest
                );
                logger.info(
                    "✅ Gateway response received - Status: {}",
                    gatewayResponse.getStatus()
                );

                // Update Transaction Status
                if ("SUCCESS".equalsIgnoreCase(gatewayResponse.getStatus())) {
                    // Don't override escrow status - keep as PENDING for escrow transactions
                    if (!requiresEscrow) {
                        transaction.setStatus(TransactionStatus.SUCCESS);
                        logger.info("✅ Transaction marked as SUCCESS");
                    } else {
                        logger.info(
                            "⏳ Escrow transaction remains PENDING - will auto-complete in 30 minutes"
                        );
                    }

                    // Publish TransactionCompletedEvent to Kafka AFTER transaction commit
                    Transaction finalTransaction = transaction;
                    UUID finalSenderWalletId = senderWalletId;
                    UUID finalReceiverWalletId = receiverWalletId;
                    BigDecimal finalAmount = amount;
                    String finalCorrelationId = correlationId;

                    TransactionSynchronizationManager.registerSynchronization(
                        new TransactionSynchronization() {
                            @Override
                            public void afterCommit() {
                                MDC.put("correlationId", finalCorrelationId);
                                publishTransactionCompletedEvent(
                                    finalTransaction,
                                    finalSenderWalletId,
                                    finalReceiverWalletId,
                                    finalAmount,
                                    finalCorrelationId
                                );
                                MDC.clear();
                            }

                            @Override
                            public void afterCompletion(int status) {
                                if (status == STATUS_ROLLED_BACK) {
                                    logger.error(
                                        "❌ Transaction rolled back - ID: {} | CorrelationId: {}",
                                        finalTransaction.getId(),
                                        finalCorrelationId
                                    );
                                }
                            }
                        }
                    );
                } else if (
                    "PENDING".equalsIgnoreCase(gatewayResponse.getStatus())
                ) {
                    transaction.setStatus(TransactionStatus.PENDING);
                    logger.warn(
                        "⏳ Transaction status: PENDING - Manual verification may be required"
                    );
                } else {
                    transaction.setStatus(TransactionStatus.FAILED);
                    logger.error(
                        "❌ Transaction FAILED - Gateway status: {}",
                        gatewayResponse.getStatus()
                    );
                }

                transactionRepository.saveAndFlush(transaction);
                logger.info(
                    "✅ P2P Transfer completed successfully - Transaction ID: {}",
                    transaction.getId()
                );
            } catch (
                InsufficientFundsException
                | WalletNotFoundException
                | PppsException e
            ) {
                logger.error(
                    "❌ Transfer failed - {}: {}",
                    e.getClass().getSimpleName(),
                    e.getMessage()
                );
                throw e;
            } catch (Exception e) {
                logger.error(
                    "❌ Unexpected error during transfer - CorrelationId: {}",
                    MDC.get("correlationId"),
                    e
                );
                throw new PppsException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Transaction failed: " + e.getMessage()
                );
            } finally {
                MDC.clear();
            }
        });
    }

    // Kafka publishing with success/failure logging
    private void publishTransactionCompletedEvent(
        Transaction transaction,
        UUID senderWalletId,
        UUID receiverWalletId,
        BigDecimal amount,
        String correlationId
    ) {
        String currentCorrelationId = MDC.get("correlationId");
        if (currentCorrelationId == null) {
            MDC.put("correlationId", correlationId);
        }

        try {
            logger.info(
                "📤 Publishing TransactionCompletedEvent to Kafka - Tx ID: {} | Topic: {}",
                transaction.getId(),
                transactionCompletedTopic
            );

            TransactionCompletedEvent event = new TransactionCompletedEvent(
                transaction.getId(),
                senderWalletId,
                receiverWalletId,
                amount,
                transaction.getStatus().name(),
                Instant.now()
            );

            CompletableFuture<SendResult<String, Object>> future =
                kafkaTemplate.send(
                    transactionCompletedTopic,
                    transaction.getId().toString(),
                    event
                );

            future.whenComplete((result, ex) -> {
                String completionCorrelationId = MDC.get("correlationId");

                if (ex == null) {
                    logger.info(
                        "[{}] ✅ Kafka message sent successfully - Tx ID: {} | Partition: {} | Offset: {} | Topic: {}",
                        completionCorrelationId,
                        transaction.getId(),
                        result.getRecordMetadata().partition(),
                        result.getRecordMetadata().offset(),
                        transactionCompletedTopic
                    );
                } else {
                    logger.error(
                        "[{}] ❌ Failed to publish Kafka message - Tx ID: {} | Topic: {} | Error: {}",
                        completionCorrelationId,
                        transaction.getId(),
                        transactionCompletedTopic,
                        ex.getMessage(),
                        ex
                    );
                }
            });
        } catch (Exception e) {
            logger.error(
                "[{}] ❌ Exception during Kafka publish attempt - Tx ID: {} | Topic: {}",
                currentCorrelationId,
                transaction.getId(),
                transactionCompletedTopic,
                e
            );
            // Transaction is already committed — do NOT re-throw.
            // Consider alerting ops team or implementing compensating actions.
        } finally {
            if (currentCorrelationId == null) {
                MDC.remove("correlationId");
            }
        }
    }

    // Verifies user's PIN using bcrypt password encoder.
    private boolean verifyPin(String providedPin, String hashedPin) {
        if (hashedPin == null) {
            throw new PppsException(HttpStatus.BAD_REQUEST, "User PIN not set");
        }
        return passwordEncoder.matches(providedPin, hashedPin);
    }
}
