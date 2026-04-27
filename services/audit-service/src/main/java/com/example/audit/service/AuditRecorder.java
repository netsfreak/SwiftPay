package com.example.audit.service;

import com.example.audit.event.DepositCompletedEvent;
import com.example.audit.event.TransactionCompletedEvent;
import com.example.audit.event.WithdrawalCompletedEvent;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class AuditRecorder {
    private static final Logger log = LoggerFactory.getLogger(AuditRecorder.class);

    public void recordTransfer(TransactionCompletedEvent event) {
        log.info("Recorded transfer audit event: transactionId={}, status={}",
                event.getTransactionId(), event.getStatus());
    }

    public void recordDeposit(DepositCompletedEvent event) {
        log.info("Recorded deposit audit event: transactionId={}, status={}",
                event.getTransactionId(), event.getStatus());
    }

    public void recordWithdrawal(WithdrawalCompletedEvent event) {
        log.info("Recorded withdrawal audit event: transactionId={}, status={}",
                event.getTransactionId(), event.getStatus());
    }
}
