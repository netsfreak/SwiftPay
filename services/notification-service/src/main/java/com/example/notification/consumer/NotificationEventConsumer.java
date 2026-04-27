package com.example.notification.consumer;

import com.example.notification.config.KafkaTopics;
import com.example.notification.event.DepositCompletedEvent;
import com.example.notification.event.TransactionCompletedEvent;
import com.example.notification.event.WithdrawalCompletedEvent;
import com.example.notification.service.NotificationDispatcher;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component
public class NotificationEventConsumer {
    private static final Logger log = LoggerFactory.getLogger(NotificationEventConsumer.class);

    private final NotificationDispatcher notificationDispatcher;

    public NotificationEventConsumer(NotificationDispatcher notificationDispatcher) {
        this.notificationDispatcher = notificationDispatcher;
    }

    @KafkaListener(topics = KafkaTopics.TRANSACTIONS_COMPLETED, groupId = "${app.kafka.consumer-group}")
    public void onTransferCompleted(TransactionCompletedEvent event) {
        log.info("Received transfer completion event: {}", event.getTransactionId());
        notificationDispatcher.sendTransferNotification(event);
    }

    @KafkaListener(topics = KafkaTopics.DEPOSIT_COMPLETED, groupId = "${app.kafka.consumer-group}")
    public void onDepositCompleted(DepositCompletedEvent event) {
        log.info("Received deposit completion event: {}", event.getTransactionId());
        notificationDispatcher.sendDepositNotification(event);
    }

    @KafkaListener(topics = KafkaTopics.WITHDRAWAL_COMPLETED, groupId = "${app.kafka.consumer-group}")
    public void onWithdrawalCompleted(WithdrawalCompletedEvent event) {
        log.info("Received withdrawal completion event: {}", event.getTransactionId());
        notificationDispatcher.sendWithdrawalNotification(event);
    }
}
