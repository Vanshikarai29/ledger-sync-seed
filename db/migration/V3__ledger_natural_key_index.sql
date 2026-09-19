-- Speeds up the natural-key lookup SqlLedgerStore.save() does before every
-- write (account_last4, direction, amount, occurred_at). NOT UNIQUE:
-- V2__seed.sql contains legacy rows that already violate uniqueness on
-- purpose (see incident/, Backfill) and this migration must not fail on them.
CREATE INDEX IF NOT EXISTS idx_ledger_natural_key
    ON ledger (account_last4, direction, amount, occurred_at);
