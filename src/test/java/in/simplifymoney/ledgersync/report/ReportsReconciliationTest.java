package in.simplifymoney.ledgersync.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import in.simplifymoney.ledgersync.ingest.BalanceSnapshot;
import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ReportsReconciliationTest {

    private static NormalizedTxn txn(String when, Direction dir, String amount) {
        return new NormalizedTxn("4821", OffsetDateTime.parse(when), dir,
                new BigDecimal(amount), dir == Direction.DEBIT ? Category.SPEND : Category.INCOME,
                "TEST", List.of("m-" + when));
    }

    private static BalanceSnapshot snap(String when, String balance) {
        return new BalanceSnapshot("4821", OffsetDateTime.parse(when), new BigDecimal(balance));
    }

    @Test
    void aCleanChainHasNoDiscrepancies() {
        List<NormalizedTxn> ledger = List.of(
                txn("2026-07-01T10:00:00+05:30", Direction.DEBIT, "100.00"),
                txn("2026-07-01T11:00:00+05:30", Direction.CREDIT, "50.00"));
        List<BalanceSnapshot> snapshots = List.of(
                snap("2026-07-01T10:00:00+05:30", "900.00"),
                snap("2026-07-01T11:00:00+05:30", "950.00"));

        Map<String, Object> report = Reports.reconciliation(ledger, snapshots);

        assertTrue(((List<?>) report.get("discrepancies")).isEmpty());
    }

    @Test
    void anUnexplainedBalanceJumpIsFlagged() {
        // a real debit happened between these two points that no message
        // in the corpus evidences - exactly INC-corpus's Rs.7500 gap
        List<NormalizedTxn> ledger = List.of(
                txn("2026-07-01T10:00:00+05:30", Direction.DEBIT, "100.00"),
                txn("2026-07-01T12:00:00+05:30", Direction.DEBIT, "75.00"));
        List<BalanceSnapshot> snapshots = List.of(
                snap("2026-07-01T10:00:00+05:30", "900.00"),
                snap("2026-07-01T12:00:00+05:30", "825.00")); // should be 825, is short by 7500

        // introduce the exact scenario: expected 900-75=825 becomes 825-7500 in reality
        snapshots = List.of(
                snap("2026-07-01T10:00:00+05:30", "36054.05"),
                snap("2026-07-01T12:00:00+05:30", "28479.05"));

        Map<String, Object> report = Reports.reconciliation(ledger, snapshots);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> discrepancies = (List<Map<String, Object>>) report.get("discrepancies");
        assertEquals(1, discrepancies.size());
        assertEquals("7500.00", discrepancies.get(0).get("amount"));
        assertEquals("4821", discrepancies.get(0).get("account_last4"));
    }

    @Test
    void aSingleGapDoesNotCascadeIntoLaterFalsePositives() {
        List<NormalizedTxn> ledger = List.of(
                txn("2026-07-01T10:00:00+05:30", Direction.DEBIT, "100.00"),
                txn("2026-07-01T12:00:00+05:30", Direction.DEBIT, "75.00"),
                txn("2026-07-01T14:00:00+05:30", Direction.DEBIT, "25.00"));
        List<BalanceSnapshot> snapshots = List.of(
                snap("2026-07-01T10:00:00+05:30", "36054.05"),
                snap("2026-07-01T12:00:00+05:30", "28479.05"), // the one real gap
                snap("2026-07-01T14:00:00+05:30", "28454.05")); // 28479.05 - 25.00, clean from here

        Map<String, Object> report = Reports.reconciliation(ledger, snapshots);

        assertEquals(1, ((List<?>) report.get("discrepancies")).size());
    }
}
