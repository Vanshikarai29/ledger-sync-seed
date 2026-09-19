package in.simplifymoney.ledgersync.ingest;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * The account balance the bank quoted immediately after a transaction, when
 * it quoted one (SMS almost always do; the transaction-alert emails in this
 * corpus never do).
 *
 * NormalizedTxn is frozen and has no room for this, so it travels alongside
 * the ledger as its own list - see TransactionAssembler.Assembled. Reports
 * uses it to check the ledger against the bank's own arithmetic: walk each
 * account's snapshots in order and confirm the running balance implied by our
 * transactions matches what the bank said. Where it does not, something
 * happened that no message in the corpus explains.
 */
public record BalanceSnapshot(String accountLast4, OffsetDateTime occurredAt, BigDecimal balance) {
}
