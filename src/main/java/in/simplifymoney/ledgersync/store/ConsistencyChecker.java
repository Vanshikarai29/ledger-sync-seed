package in.simplifymoney.ledgersync.store;

import in.simplifymoney.ledgersync.ingest.TransactionAssembler;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import in.simplifymoney.ledgersync.parse.ParsedTxn;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Proves the two stores agree, and says precisely where they do not.
 *
 * "Agree" is checked against what SQL SHOULD produce after a correct
 * backfill - the same natural-key merge and re-categorisation Backfill
 * itself does (see Backfill's javadoc for why) - not against SQL's raw,
 * duplicate-laden rows directly. Comparing raw row counts would flag the
 * legacy duplicates in V2__seed.sql as a "mismatch" even on a perfect
 * backfill, which is not a real divergence.
 *
 * Matching is two-tier so a single tampered field cannot hide a row:
 *  1. by natural key (accountLast4, direction, amount, occurredAt) - catches
 *     a changed category, merchant, or a shrunk/grown source_message_ids set,
 *     none of which affect the key
 *  2. for anything left unmatched after that, by shared source_message_ids -
 *     if a document and a SQL-derived transaction share even one message id
 *     but landed under different keys, they are almost certainly the same
 *     real transaction with amount/direction/occurredAt altered, and are
 *     reported as a field-level diff rather than as one missing + one extra
 *  Only what is left after both passes is reported as genuinely missing from
 *  or added to the document store.
 */
public final class ConsistencyChecker {

    private final SqlLedgerStore sql;
    private final DocumentStore documents;

    public ConsistencyChecker(SqlLedgerStore sql, DocumentStore documents) {
        this.sql = sql;
        this.documents = documents;
    }

    private record Key(String accountLast4, Direction direction, BigDecimal amount, OffsetDateTime occurredAt) {
        static Key of(NormalizedTxn t) {
            return new Key(t.accountLast4(), t.direction(), t.amount(), t.occurredAt());
        }
    }

    public List<Divergence> check() {
        Map<Key, NormalizedTxn> expected = expectedFromSql();
        Map<Key, NormalizedTxn> actual = new LinkedHashMap<>();
        for (NormalizedTxn t : documents.allForMigrationTooling()) actual.put(Key.of(t), t);

        List<Divergence> out = new ArrayList<>();
        List<NormalizedTxn> unmatchedExpected = new ArrayList<>();
        List<NormalizedTxn> unmatchedActual = new ArrayList<>();

        for (Map.Entry<Key, NormalizedTxn> e : expected.entrySet()) {
            NormalizedTxn doc = actual.remove(e.getKey());
            if (doc == null) {
                unmatchedExpected.add(e.getValue());
            } else {
                out.addAll(fieldDiffs(e.getValue(), doc));
            }
        }
        unmatchedActual.addAll(actual.values());

        // second pass: pair up leftovers that share a source_message_id -
        // same real transaction, but a key field was altered
        for (NormalizedTxn exp : new ArrayList<>(unmatchedExpected)) {
            NormalizedTxn match = null;
            for (NormalizedTxn act : unmatchedActual) {
                if (!disjoint(exp.sourceMessageIds(), act.sourceMessageIds())) {
                    match = act;
                    break;
                }
            }
            if (match != null) {
                out.addAll(fieldDiffs(exp, match));
                unmatchedExpected.remove(exp);
                unmatchedActual.remove(match);
            }
        }

        for (NormalizedTxn exp : unmatchedExpected) {
            out.add(new Divergence("transaction missing from document store: "
                    + describe(exp), describe(exp), "<absent>"));
        }
        for (NormalizedTxn act : unmatchedActual) {
            out.add(new Divergence("transaction present in document store but not in SQL: "
                    + describe(act), "<absent>", describe(act)));
        }

        return out;
    }

    private Map<Key, NormalizedTxn> expectedFromSql() {
        List<ParsedTxn> evidence = new ArrayList<>();
        for (NormalizedTxn row : sql.all()) {
            for (String messageId : row.sourceMessageIds()) {
                evidence.add(new ParsedTxn(row.accountLast4(), row.occurredAt(), row.direction(),
                        row.amount(), row.merchant(), null, messageId));
            }
        }
        Map<Key, NormalizedTxn> out = new LinkedHashMap<>();
        for (NormalizedTxn t : TransactionAssembler.assemble(evidence).transactions()) {
            out.put(Key.of(t), t);
        }
        return out;
    }

    private static boolean disjoint(List<String> a, List<String> b) {
        Set<String> as = new TreeSet<>(a);
        for (String x : b) if (as.contains(x)) return false;
        return true;
    }

    private List<Divergence> fieldDiffs(NormalizedTxn expected, NormalizedTxn actual) {
        List<Divergence> out = new ArrayList<>();
        String where = expected.accountLast4() + "/" + expected.direction() + "/"
                + expected.amount() + "/" + expected.occurredAt();

        if (!expected.accountLast4().equals(actual.accountLast4())) {
            out.add(new Divergence("account_last4 for " + where, expected.accountLast4(), actual.accountLast4()));
        }
        if (expected.direction() != actual.direction()) {
            out.add(new Divergence("direction for " + where, expected.direction().name(), actual.direction().name()));
        }
        if (expected.amount().compareTo(actual.amount()) != 0) {
            out.add(new Divergence("amount for " + where, expected.amount().toPlainString(), actual.amount().toPlainString()));
        }
        if (!expected.occurredAt().isEqual(actual.occurredAt())) {
            out.add(new Divergence("occurred_at for " + where, expected.occurredAt().toString(), actual.occurredAt().toString()));
        }
        if (expected.category() != actual.category()) {
            out.add(new Divergence("category for " + where, expected.category().name(), actual.category().name()));
        }
        if (!java.util.Objects.equals(expected.merchant(), actual.merchant())) {
            out.add(new Divergence("merchant for " + where, expected.merchant(), actual.merchant()));
        }
        Set<String> expIds = new TreeSet<>(expected.sourceMessageIds());
        Set<String> actIds = new TreeSet<>(actual.sourceMessageIds());
        if (!expIds.equals(actIds)) {
            out.add(new Divergence("source_message_ids for " + where, expIds.toString(), actIds.toString()));
        }
        return out;
    }

    private static String describe(NormalizedTxn t) {
        return "%s/%s/%s/%s cat=%s merchant=%s ids=%s".formatted(
                t.accountLast4(), t.direction(), t.amount(), t.occurredAt(),
                t.category(), t.merchant(), t.sourceMessageIds());
    }

    /** One place the two stores disagree. */
    public record Divergence(String what, String inSql, String inDocuments) {}
}
