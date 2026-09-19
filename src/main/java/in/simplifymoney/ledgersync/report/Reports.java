package in.simplifymoney.ledgersync.report;

import in.simplifymoney.ledgersync.ingest.BalanceSnapshot;
import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/** The three reports the assignment asks for. */
public final class Reports {

    private Reports() {}

    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);

    public static Map<String, Object> summary(List<NormalizedTxn> ledger) {
        Map<String, Object> accounts = new LinkedHashMap<>();
        for (String acct : new TreeSet<>(ledger.stream()
                .map(NormalizedTxn::accountLast4).toList())) {

            BigDecimal spend = ZERO;
            BigDecimal income = ZERO;
            BigDecimal microTotal = ZERO;
            int microCount = 0;
            BigDecimal transferredOut = ZERO;
            BigDecimal transferredIn = ZERO;

            for (NormalizedTxn t : ledger) {
                if (!t.accountLast4().equals(acct)) continue;
                switch (t.category()) {
                    case SPEND -> spend = spend.add(t.amount());
                    case INCOME -> income = income.add(t.amount());
                    case MICRO -> {
                        microTotal = microTotal.add(t.amount());
                        microCount++;
                    }
                    case TRANSFER -> {
                        if (t.direction() == Direction.DEBIT) transferredOut = transferredOut.add(t.amount());
                        else transferredIn = transferredIn.add(t.amount());
                    }
                }
            }

            Map<String, Object> a = new LinkedHashMap<>();
            a.put("spend", spend.toPlainString());
            a.put("income", income.toPlainString());
            a.put("micro_count", microCount);
            a.put("micro_total", microTotal.toPlainString());
            a.put("transferred_out", transferredOut.toPlainString());
            a.put("transferred_in", transferredIn.toPlainString());
            accounts.put(acct, a);
        }
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("accounts", accounts);
        return doc;
    }

    public static Map<String, Object> ledgerDocument(List<NormalizedTxn> ledger) {
        List<Object> rows = ledger.stream().map(t -> {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("account_last4", t.accountLast4());
            r.put("occurred_at", t.occurredAt().toString());
            r.put("direction", t.direction().name().toLowerCase());
            r.put("amount", t.amount().toPlainString());
            r.put("category", t.category().name());
            r.put("merchant", t.merchant());
            r.put("source_message_ids", t.sourceMessageIds());
            return (Object) r;
        }).toList();
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("transactions", rows);
        return doc;
    }

    /**
     * Walks each account's BalanceSnapshots in order and checks them against
     * the running balance our own ledger implies. Every snapshot is a point
     * where the bank told us exactly what the balance was; between two
     * snapshots, the sum of our transactions' signed amounts should exactly
     * cover the difference. When it does not, something happened on the
     * account that no message in the corpus explains - the balance moved,
     * but we have no transaction, and so no evidence, to show for it.
     *
     * This deliberately does not try to guess what the missing transaction
     * was. It reports the account, when the gap was detected, and the size
     * of the gap - because inventing a transaction we have no
     * source_message_ids for would violate the one rule this whole service
     * exists to uphold: a transaction is only real if we can point to the
     * message that says so.
     *
     * One snapshot's mismatch does not cascade into every later one: once a
     * gap is reported, the running balance resyncs to the bank's own figure
     * and checking continues from there.
     */
    public static Map<String, Object> reconciliation(List<NormalizedTxn> ledger,
                                                       List<BalanceSnapshot> snapshots) {
        List<Object> discrepancies = new ArrayList<>();

        Map<String, List<NormalizedTxn>> txnsByAccount = new LinkedHashMap<>();
        for (NormalizedTxn t : ledger) {
            txnsByAccount.computeIfAbsent(t.accountLast4(), k -> new ArrayList<>()).add(t);
        }
        Map<String, List<BalanceSnapshot>> snapsByAccount = new LinkedHashMap<>();
        for (BalanceSnapshot s : snapshots) {
            snapsByAccount.computeIfAbsent(s.accountLast4(), k -> new ArrayList<>()).add(s);
        }

        for (String acct : new TreeSet<>(snapsByAccount.keySet())) {
            List<NormalizedTxn> txns = txnsByAccount.getOrDefault(acct, List.of()).stream()
                    .sorted(Comparator.comparing(NormalizedTxn::occurredAt)).toList();
            List<BalanceSnapshot> snaps = snapsByAccount.get(acct).stream()
                    .sorted(Comparator.comparing(BalanceSnapshot::occurredAt)).toList();

            BigDecimal runningBalance = null;
            OffsetDateTime lastReconciledAt = null;
            BigDecimal accumulated = ZERO;
            int txnIndex = 0;

            for (BalanceSnapshot snap : snaps) {
                // fold in every transaction up to and including this snapshot's moment
                while (txnIndex < txns.size()
                        && !txns.get(txnIndex).occurredAt().isAfter(snap.occurredAt())) {
                    NormalizedTxn t = txns.get(txnIndex);
                    accumulated = t.direction() == Direction.DEBIT
                            ? accumulated.subtract(t.amount())
                            : accumulated.add(t.amount());
                    txnIndex++;
                }

                if (runningBalance == null) {
                    // first snapshot we see: nothing to check yet, just anchor here
                    runningBalance = snap.balance();
                    lastReconciledAt = snap.occurredAt();
                    accumulated = ZERO;
                    continue;
                }

                BigDecimal expected = runningBalance.add(accumulated);
                if (expected.compareTo(snap.balance()) != 0) {
                    BigDecimal gap = snap.balance().subtract(expected);
                    Map<String, Object> d = new LinkedHashMap<>();
                    d.put("account_last4", acct);
                    d.put("occurred_at", snap.occurredAt().toString());
                    d.put("amount", gap.abs().toPlainString());
                    d.put("note", "balance moved by an unexplained %s of Rs.%s between %s and %s; "
                            .formatted(gap.signum() < 0 ? "debit" : "credit", gap.abs().toPlainString(),
                                    lastReconciledAt, snap.occurredAt())
                            + "no message in the corpus accounts for it");
                    discrepancies.add(d);
                }

                // resync to the bank's figure either way, so one gap does not
                // cascade into flagging every snapshot after it
                runningBalance = snap.balance();
                lastReconciledAt = snap.occurredAt();
                accumulated = ZERO;
            }
        }

        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("discrepancies", discrepancies);
        return doc;
    }

    public static Map<Category, BigDecimal> byCategory(List<NormalizedTxn> ledger) {
        Map<Category, BigDecimal> out = new LinkedHashMap<>();
        for (Category c : Category.values()) out.put(c, ZERO);
        for (NormalizedTxn t : ledger) {
            out.put(t.category(), out.get(t.category()).add(t.amount()));
        }
        return out;
    }
}
