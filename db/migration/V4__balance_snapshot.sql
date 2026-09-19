-- The account balance the bank quoted right after a transaction. Not every
-- transaction has one (the alert emails in this corpus never quote a
-- balance) - this table only holds the ones that do. Reports.reconciliation()
-- walks these per account, in order, to confirm the ledger's running balance
-- matches what the bank said - and to flag it when it does not.
CREATE TABLE IF NOT EXISTS balance_snapshot (
    account_last4 VARCHAR(4)     NOT NULL,
    occurred_at    VARCHAR(40)    NOT NULL,
    balance        DECIMAL(14, 2) NOT NULL,
    PRIMARY KEY (account_last4, occurred_at)
);
