-- ========================================================
-- PostgreSQL Database Optimization Script for 250 TPS
-- ========================================================
-- This script creates critical indexes for high-throughput
-- payment systems. Run this after initial schema creation.
-- ========================================================

-- ========================================================
-- 1. WALLET LEDGER INDEXES (Critical for debit/credit)
-- ========================================================
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_wallet_ledger_wallet_id 
  ON wallet_ledger(wallet_id) 
  WHERE deleted_at IS NULL;

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_wallet_ledger_wallet_created 
  ON wallet_ledger(wallet_id, created_at DESC) 
  WHERE deleted_at IS NULL;

-- ========================================================
-- 2. TRANSACTION INDEXES (For transaction lookups)
-- ========================================================
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_transactions_status_created 
  ON transactions(status, initiated_at DESC) 
  WHERE deleted_at IS NULL;

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_transactions_sender_receiver 
  ON transactions(sender_wallet_id, receiver_wallet_id, initiated_at DESC) 
  WHERE deleted_at IS NULL;

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_transactions_idempotency_key 
  ON transactions(idempotency_key) 
  WHERE deleted_at IS NULL AND idempotency_key IS NOT NULL;

-- ========================================================
-- 3. USER & WALLET INDEXES (For user lookups)
-- ========================================================
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_users_email 
  ON users(email) 
  WHERE deleted_at IS NULL;

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_users_phone 
  ON users(phone) 
  WHERE deleted_at IS NULL;

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_wallets_user_id 
  ON wallets(user_id) 
  WHERE deleted_at IS NULL;

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_wallets_currency 
  ON wallets(currency, user_id) 
  WHERE deleted_at IS NULL;

-- ========================================================
-- 4. AUDIT LOG INDEXES (For compliance & investigation)
-- ========================================================
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_audit_logs_entity_date 
  ON audit_logs(entity_type, entity_id, created_at DESC) 
  WHERE deleted_at IS NULL;

-- ========================================================
-- 5. PARTIAL INDEXES (For active/pending records only)
-- ========================================================
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_pending_transfers 
  ON transactions(initiated_at DESC) 
  WHERE status = 'PENDING' AND deleted_at IS NULL;

-- ========================================================
-- 6. MATERIALIZED VIEW for balance cache (Optional)
-- ========================================================
-- This is useful if you want to cache current balance state
CREATE MATERIALIZED VIEW IF NOT EXISTS wallet_balance_cache AS
  SELECT 
    w.id as wallet_id,
    w.user_id,
    COALESCE(SUM(CASE WHEN wl.credit_amount IS NOT NULL THEN wl.credit_amount ELSE 0 END), 0) -
    COALESCE(SUM(CASE WHEN wl.debit_amount IS NOT NULL THEN wl.debit_amount ELSE 0 END), 0) as current_balance,
    MAX(wl.created_at) as last_transaction_at
  FROM wallets w
  LEFT JOIN wallet_ledger wl ON w.id = wl.wallet_id
  WHERE w.deleted_at IS NULL
  GROUP BY w.id, w.user_id;

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_balance_cache_wallet 
  ON wallet_balance_cache(wallet_id);

-- ========================================================
-- 7. CLUSTER INDEXES (For sequential reads)
-- ========================================================
-- Note: CLUSTER is performed once, then data will benefit from clustering
-- CLUSTER wallet_ledger USING idx_wallet_ledger_wallet_created;
-- CLUSTER transactions USING idx_transactions_status_created;

-- ========================================================
-- 8. STATISTICS & ANALYZE
-- ========================================================
-- Collect statistics for query planner optimization
ANALYZE wallet_ledger;
ANALYZE transactions;
ANALYZE wallets;
ANALYZE users;
ANALYZE audit_logs;

-- ========================================================
-- 9. VACUUM CONFIGURATION (For maintenance)
-- ========================================================
-- Set aggressive auto-vacuum for high-transaction tables
ALTER TABLE wallet_ledger SET (
  autovacuum_vacuum_scale_factor = 0.01,
  autovacuum_analyze_scale_factor = 0.005
);

ALTER TABLE transactions SET (
  autovacuum_vacuum_scale_factor = 0.01,
  autovacuum_analyze_scale_factor = 0.005
);

-- ========================================================
-- 10. REFRESH MATERIALIZED VIEW (Run periodically)
-- ========================================================
-- REFRESH MATERIALIZED VIEW CONCURRENTLY wallet_balance_cache;
