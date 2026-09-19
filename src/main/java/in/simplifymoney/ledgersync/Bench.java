package in.simplifymoney.ledgersync;

import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import in.simplifymoney.ledgersync.store.DocumentStore;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Seeds 100,000 synthetic transactions directly (bypassing parsing/ingest -
 * this is a storage-layer benchmark, not another parser test) and prints the
 * examined-vs-returned numbers for each of the three queries, for the
 * README.
 *
 * Only compiled against the DocumentStore interface, like the rest of the
 * main tree - see App.openDocumentStore() for why.
 */
final class Bench {

    private static final int TRANSACTION_COUNT = 100_000;
    private static final String[] ACCOUNTS = {"1111", "2222", "3333", "4444", "5555"};
    private static final int MONTHS = 24;

    private Bench() {}

    static void run(DocumentStore docs) {
        System.out.println("seeding " + TRANSACTION_COUNT + " synthetic transactions...");
        OffsetDateTime start = OffsetDateTime.of(2024, 1, 1, 0, 0, 0, 0, ZoneOffset.ofHoursMinutes(5, 30));

        String probeMessageId = null;
        String probeAccount = ACCOUNTS[0];
        YearMonth probeMonth = YearMonth.from(start.plusMonths(1));

        for (int i = 0; i < TRANSACTION_COUNT; i++) {
            String account = ACCOUNTS[i % ACCOUNTS.length];
            OffsetDateTime occurredAt = start.plusMinutes(i * 7L).plusMonths(i % MONTHS);
            Direction dir = i % 3 == 0 ? Direction.CREDIT : Direction.DEBIT;
            Category cat = switch (i % 5) {
                case 0 -> Category.INCOME;
                case 1, 2 -> Category.MICRO;
                case 3 -> Category.TRANSFER;
                default -> Category.SPEND;
            };
            BigDecimal amount = new BigDecimal(cat == Category.MICRO ? "42.50" : "1250.75");
            String messageId = "bench-" + i;

            if (account.equals(probeAccount) && YearMonth.from(occurredAt).equals(probeMonth) && probeMessageId == null) {
                probeMessageId = messageId;
            }

            docs.save(new NormalizedTxn(account, occurredAt, dir, amount, cat,
                    "BENCH MERCHANT " + (i % 50), List.of(messageId)));

            if (i > 0 && i % 20_000 == 0) System.out.println("  " + i + "...");
        }
        System.out.println("seeded.");

        System.out.println("\nQ1: forAccountMonth(" + probeAccount + ", " + probeMonth + ")");
        report(docs.examineForAccountMonth(probeAccount, probeMonth));

        System.out.println("\nQ2: categoryTotals(" + probeAccount + ")");
        report(docs.examineForCategoryTotals(probeAccount));

        System.out.println("\nQ3: byMessageId(" + probeMessageId + ")");
        report(docs.examineForMessageId(probeMessageId));
    }

    private static void report(int[] examinedAndReturned) {
        System.out.printf("  examined=%d  returned=%d%n", examinedAndReturned[0], examinedAndReturned[1]);
    }
}
