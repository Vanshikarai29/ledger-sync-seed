package in.simplifymoney.ledgersync.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Runs Backfill and ConsistencyChecker against the real db/migration/*.sql
 * scripts - including V2__seed.sql, the deliberately dirty legacy data - and
 * a FakeDocumentStore standing in for MongoDocumentStore. What is under test
 * here is the natural-key merge/recategorisation/comparison logic, which is
 * exactly the same code path MongoDocumentStore drives; only the storage
 * underneath differs, and neither Backfill nor ConsistencyChecker can see
 * that difference (they only see LedgerStore/DocumentStore).
 */
class BackfillAndConsistencyCheckerTest {

    @TempDir
    Path tmp;

    private SqlLedgerStore freshSqlStore() {
        SqlLedgerStore sql = new SqlLedgerStore(tmp.resolve("ledger"));
        sql.migrate(Path.of("db", "migration"));
        return sql;
    }

    @Test
    void backfillDedupesTheDirtyLegacyRowsAndRecategorisesTheTransferPair() {
        try (SqlLedgerStore sql = freshSqlStore()) {
            long rawRows = sql.count();

            FakeDocumentStore docs = new FakeDocumentStore();
            Backfill.Result result = new Backfill(sql, docs).run();

            assertEquals(rawRows, result.read());
            // V2__seed.sql's 15 rows collapse to 10 distinct transactions - see
            // Backfill's javadoc for the row-by-row accounting
            assertEquals(rawRows - 5, result.written());
            assertEquals(5L, result.skipped());
            assertEquals(result.written(), docs.size());

            // the legacy IMPS/P2A/PARAG KAPOOR pair was written as SPEND/INCOME
            // by a pipeline with no transfer detection - Backfill must
            // re-derive it as TRANSFER, not copy the old category forward
            NormalizedTxn leg = docs.allForMigrationTooling().stream()
                    .filter(t -> "PARAG KAPOOR".equals(t.merchant()) || (t.merchant() != null && t.merchant().contains("PARAG KAPOOR")))
                    .findFirst().orElseThrow();
            assertEquals(in.simplifymoney.ledgersync.model.Category.TRANSFER, leg.category());
        }
    }

    @Test
    void backfillIsIdempotent() {
        try (SqlLedgerStore sql = freshSqlStore()) {
            FakeDocumentStore docs = new FakeDocumentStore();
            Backfill backfill = new Backfill(sql, docs);

            Backfill.Result first = backfill.run();
            int sizeAfterFirst = docs.size();
            Backfill.Result second = backfill.run();

            assertEquals(first, second);
            assertEquals(sizeAfterFirst, docs.size());
        }
    }

    @Test
    void aCleanBackfillHasNoDivergences() {
        try (SqlLedgerStore sql = freshSqlStore()) {
            FakeDocumentStore docs = new FakeDocumentStore();
            new Backfill(sql, docs).run();

            List<ConsistencyChecker.Divergence> divergences = new ConsistencyChecker(sql, docs).check();

            assertTrue(divergences.isEmpty(), "expected no divergences, got " + divergences);
        }
    }

    @Test
    void aTamperedAmountIsFoundAndNamedPrecisely() {
        try (SqlLedgerStore sql = freshSqlStore()) {
            FakeDocumentStore docs = new FakeDocumentStore();
            new Backfill(sql, docs).run();

            // m-legacy-0014/0015: 4821 DEBIT 30.00 UPI/CHAIWALA
            docs.tamper("4821", Direction.DEBIT, new BigDecimal("30.00"),
                    OffsetDateTime.parse("2026-06-29T13:22:00+05:30"),
                    t -> new NormalizedTxn(t.accountLast4(), t.occurredAt(), t.direction(),
                            new BigDecimal("999.99"), t.category(), t.merchant(), t.sourceMessageIds()));

            List<ConsistencyChecker.Divergence> divergences = new ConsistencyChecker(sql, docs).check();

            assertEquals(1, divergences.size());
            ConsistencyChecker.Divergence d = divergences.get(0);
            assertTrue(d.what().contains("amount"), "expected an amount divergence, got: " + d);
            assertEquals("30.00", d.inSql());
            assertEquals("999.99", d.inDocuments());
        }
    }

    @Test
    void aDeletedDocumentIsReportedAsMissing() {
        try (SqlLedgerStore sql = freshSqlStore()) {
            FakeDocumentStore docs = new FakeDocumentStore();
            new Backfill(sql, docs).run();

            // m-legacy-0011: 4821 CREDIT 45000.00 SALARY CREDIT
            docs.delete("4821", Direction.CREDIT, new BigDecimal("45000.00"),
                    OffsetDateTime.parse("2026-06-29T09:15:00+05:30"));

            List<ConsistencyChecker.Divergence> divergences = new ConsistencyChecker(sql, docs).check();

            assertEquals(1, divergences.size());
            assertTrue(divergences.get(0).what().contains("missing from document store"));
        }
    }
}
