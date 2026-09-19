package in.simplifymoney.ledgersync.store;

import in.simplifymoney.ledgersync.ingest.BalanceSnapshot;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/** Used by SelfCheck and by tests. Idempotent by natural key - see LedgerStore. */
public final class InMemoryLedgerStore implements LedgerStore {

    private record Key(String accountLast4, Direction direction, BigDecimal amount,
                        OffsetDateTime occurredAt) {
        static Key of(NormalizedTxn t) {
            return new Key(t.accountLast4(), t.direction(), t.amount(), t.occurredAt());
        }
    }

    private final Map<Key, NormalizedTxn> rows = new LinkedHashMap<>();
    private final Map<String, BalanceSnapshot> snapshots = new LinkedHashMap<>();

    @Override
    public void save(NormalizedTxn txn) {
        Key key = Key.of(txn);
        NormalizedTxn existing = rows.get(key);
        if (existing == null) {
            rows.put(key, txn);
            return;
        }
        TreeSet<String> merged = new TreeSet<>(existing.sourceMessageIds());
        merged.addAll(txn.sourceMessageIds());
        rows.put(key, new NormalizedTxn(txn.accountLast4(), txn.occurredAt(), txn.direction(),
                txn.amount(), txn.category(), existing.merchant(), List.copyOf(merged)));
    }

    @Override public List<NormalizedTxn> all() { return Collections.unmodifiableList(new ArrayList<>(rows.values())); }

    @Override public long count() { return rows.size(); }

    @Override
    public void saveBalanceSnapshot(BalanceSnapshot s) {
        snapshots.put(s.accountLast4() + "|" + s.occurredAt(), s);
    }

    @Override
    public List<BalanceSnapshot> balanceSnapshots() {
        return Collections.unmodifiableList(new ArrayList<>(snapshots.values()));
    }
}
