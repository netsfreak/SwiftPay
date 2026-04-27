package com.example.audit.config;

public final class KafkaTopics {
    public static final String TRANSACTIONS_COMPLETED = "transactions.completed";
    public static final String DEPOSIT_COMPLETED = "deposit.completed";
    public static final String WITHDRAWAL_COMPLETED = "withdrawal.completed";

    private KafkaTopics() {
    }
}
