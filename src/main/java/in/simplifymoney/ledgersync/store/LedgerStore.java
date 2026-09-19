package in.simplifymoney.ledgersync.store;

import in.simplifymoney.ledgersync.ingest.BalanceSnapshot;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import java.util.List;

/**
 * Where transactions live.
 *
 * save() is idempotent by natural key - (accountLast4, direction, amount,
 * occurredAt). Saving a transaction that already exists under that key does
 * not create a second row: it merges the new sourceMessageIds into the
 * existing one (as a union - re-saving an id already on the row is a no-op).
 * This is what lets IngestService be re-run against the same or an
 * overlapping corpus without the ledger growing.
 *
 * Implementations do NOT need to enforce this at the schema level - the
 * legacy SQL data predates this guarantee (see SqlLedgerStore, Backfill) and
 * is deliberately left as-is.
 *
 * saveBalanceSnapshot()/balanceSnapshots() are a second, smaller stream used
 * only by Reports.reconciliation() - see BalanceSnapshot.
 */
public interface LedgerStore {

    void save(NormalizedTxn txn);

    List<NormalizedTxn> all();

    long count();

    void saveBalanceSnapshot(BalanceSnapshot snapshot);

    List<BalanceSnapshot> balanceSnapshots();
}
