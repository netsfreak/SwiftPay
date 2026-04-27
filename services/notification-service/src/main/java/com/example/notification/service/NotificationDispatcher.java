package com.example.notification.service;

import com.example.notification.event.DepositCompletedEvent;
import com.example.notification.event.TransactionCompletedEvent;
import com.example.notification.event.WithdrawalCompletedEvent;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class NotificationDispatcher {
    private static final Logger log = LoggerFactory.getLogger(NotificationDispatcher.class);

    public void sendTransferNotification(TransactionCompletedEvent event) {
        log.info("Dispatching transfer notification for transaction {}", event.getTransactionId());
    }

    public void sendDepositNotification(DepositCompletedEvent event) {
        log.info("Dispatching deposit notification for transaction {}", event.getTransactionId());
    }

    public void sendWithdrawalNotification(WithdrawalCompletedEvent event) {
        log.info("Dispatching withdrawal notification for transaction {}", event.getTransactionId());
    }
}
