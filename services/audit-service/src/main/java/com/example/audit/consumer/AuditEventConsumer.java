package com.example.audit.consumer;

import com.example.audit.config.KafkaTopics;
import com.example.audit.event.DepositCompletedEvent;
import com.example.audit.event.TransactionCompletedEvent;
import com.example.audit.event.WithdrawalCompletedEvent;
import com.example.audit.service.AuditRecorder;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component
public class AuditEventConsumer {
    private static final Logger log = LoggerFactory.getLogger(AuditEventConsumer.class);

    private final AuditRecorder auditRecorder;

    public AuditEventConsumer(AuditRecorder auditRecorder) {
        this.auditRecorder = auditRecorder;
    }

    @KafkaListener(topics = KafkaTopics.TRANSACTIONS_COMPLETED, groupId = "${app.kafka.consumer-group}")
    public void onTransferCompleted(TransactionCompletedEvent event) {
        log.info("Received transfer completion event for audit: {}", event.getTransactionId());
        auditRecorder.recordTransfer(event);
    }

    @KafkaListener(topics = KafkaTopics.DEPOSIT_COMPLETED, groupId = "${app.kafka.consumer-group}")
    public void onDepositCompleted(DepositCompletedEvent event) {
        log.info("Received deposit completion event for audit: {}", event.getTransactionId());
        auditRecorder.recordDeposit(event);
    }

    @KafkaListener(topics = KafkaTopics.WITHDRAWAL_COMPLETED, groupId = "${app.kafka.consumer-group}")
    public void onWithdrawalCompleted(WithdrawalCompletedEvent event) {
        log.info("Received withdrawal completion event for audit: {}", event.getTransactionId());
        auditRecorder.recordWithdrawal(event);
    }
}
