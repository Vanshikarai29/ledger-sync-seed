package in.simplifymoney.ledgersync.store;

import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;

/**
 * An in-memory stand-in for MongoDocumentStore, used only in tests. Mirrors
 * the same natural-key idempotent save() contract (see DocumentStore,
 * MongoDocumentStore) so Backfill and ConsistencyChecker can be exercised
 * without a real MongoDB - what matters for those two is the natural-key
 * merge and comparison logic, which has nothing to do with which database
 * sits behind DocumentStore. examineForXxx() report {-1, -1}: this fake has
 * no query planner to ask, and no test here needs it to.
 */
public final class FakeDocumentStore implements DocumentStore {

    private record Key(String accountLast4, Direction direction, BigDecimal amount, OffsetDateTime occurredAt) {
        static Key of(NormalizedTxn t) {
            return new Key(t.accountLast4(), t.direction(), t.amount(), t.occurredAt());
        }
    }

    private final Map<Key, NormalizedTxn> rows = new LinkedHashMap<>();

    @Override
    public void save(NormalizedTxn t) {
        Key key = Key.of(t);
        NormalizedTxn existing = rows.get(key);
        if (existing == null) {
            rows.put(key, t);
            return;
        }
        TreeSet<String> merged = new TreeSet<>(existing.sourceMessageIds());
        merged.addAll(t.sourceMessageIds());
        rows.put(key, new NormalizedTxn(t.accountLast4(), t.occurredAt(), t.direction(),
                t.amount(), t.category(), t.merchant(), List.copyOf(merged)));
    }

    @Override
    public List<NormalizedTxn> forAccountMonth(String accountLast4, YearMonth month) {
        List<NormalizedTxn> out = new ArrayList<>();
        for (NormalizedTxn t : rows.values()) {
            if (t.accountLast4().equals(accountLast4) && YearMonth.from(t.occurredAt()).equals(month)) out.add(t);
        }
        out.sort(Comparator.comparing(NormalizedTxn::occurredAt).reversed());
        return out;
    }

    @Override
    public Map<Category, BigDecimal> categoryTotals(String accountLast4) {
        Map<Category, BigDecimal> out = new LinkedHashMap<>();
        for (Category c : Category.values()) out.put(c, BigDecimal.ZERO.setScale(2));
        for (NormalizedTxn t : rows.values()) {
            if (t.accountLast4().equals(accountLast4)) out.put(t.category(), out.get(t.category()).add(t.amount()));
        }
        return out;
    }

    @Override
    public Optional<NormalizedTxn> byMessageId(String messageId) {
        for (NormalizedTxn t : rows.values()) {
            if (t.sourceMessageIds().contains(messageId)) return Optional.of(t);
        }
        return Optional.empty();
    }

    @Override
    public List<NormalizedTxn> allForMigrationTooling() {
        return new ArrayList<>(rows.values());
    }

    @Override public int[] examineForAccountMonth(String accountLast4, YearMonth month) { return new int[] {-1, -1}; }
    @Override public int[] examineForCategoryTotals(String accountLast4) { return new int[] {-1, -1}; }
    @Override public int[] examineForMessageId(String messageId) { return new int[] {-1, -1}; }

    @Override public void close() { }

    public int size() { return rows.size(); }

    /** Test-only: simulate a grader tampering with one document's fields. */
    public void tamper(String accountLast4, Direction direction, BigDecimal amount, OffsetDateTime occurredAt,
                        java.util.function.UnaryOperator<NormalizedTxn> mutation) {
        Key key = new Key(accountLast4, direction, amount, occurredAt);
        NormalizedTxn existing = rows.get(key);
        if (existing != null) rows.put(key, mutation.apply(existing));
    }

    /** Test-only: simulate a grader deleting a document outright. */
    public void delete(String accountLast4, Direction direction, BigDecimal amount, OffsetDateTime occurredAt) {
        rows.remove(new Key(accountLast4, direction, amount, occurredAt));
    }
}
