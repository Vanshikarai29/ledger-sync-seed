package in.simplifymoney.ledgersync.ingest;

import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import in.simplifymoney.ledgersync.parse.ParsedTxn;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

/**
 * Turns a batch of ParsedTxn (one per message that looked like a transaction)
 * into the ledger's NormalizedTxn rows: one per real transaction, correctly
 * categorised.
 *
 * Two things happen here that IngestService used to skip entirely:
 *
 * 1. MERGING. "One transaction is not one message" cuts two ways in this
 *    corpus:
 *      - the same event is reported on more than one channel (an SMS and an
 *        HDFC/ICICI email for the same debit, always agreeing to the minute)
 *      - the same message is uploaded more than once (a device resync -
 *        message_id identifies the upload, not the underlying SMS, so the
 *        same body legitimately shows up under a new id later)
 *    Both collapse to the same key: (account, direction, amount, occurredAt).
 *    We are trusting that no two genuinely different transactions on the same
 *    account land on the same rupee in the same minute - true throughout this
 *    corpus, and checked by tests.
 *
 * 2. CATEGORISING. SPEND/INCOME falls out of direction. MICRO and TRANSFER do
 *    not, and guessing them from a keyword in the merchant string is wrong:
 *      - small debits to ordinary merchants (RELIANCE SMART, SPOTIFY, PVR...)
 *        stay SPEND even under Rs.100 - MICRO is specifically a small UPI
 *        debit, not "any small amount"
 *      - "NEFT INWARD SELF" and "IMPS/P2A/RAHUL SHARMA" both look like
 *        transfers by name, but neither has a matching leg anywhere in this
 *        corpus - there is no account of ours on the other side of them, so
 *        they stay INCOME/SPEND. "IMPS/P2A/PARAG KAPOOR", by contrast, shows
 *        up as a debit on one tracked account and a credit on the other, same
 *        amount, minutes apart, every time - that pairing is what makes it a
 *        TRANSFER, not the name on the label.
 *    So TRANSFER is decided by finding the other leg, not by reading the
 *    merchant field.
 */
public final class TransactionAssembler {

    private static final BigDecimal MICRO_THRESHOLD = new BigDecimal("100.00");

    /** How far apart the two legs of a self-transfer are allowed to land. The
     *  corpus shows gaps up to a few minutes (bank-to-bank posting lag); this
     *  is generous without being so wide it risks pairing unrelated events. */
    private static final Duration TRANSFER_WINDOW = Duration.ofHours(6);

    private TransactionAssembler() {}

    public static Assembled assemble(List<ParsedTxn> parsed) {
        List<Leg> legs = merge(parsed);
        pairTransfers(legs);

        List<NormalizedTxn> out = new ArrayList<>();
        List<BalanceSnapshot> snapshots = new ArrayList<>();
        for (Leg leg : legs) {
            Category category = leg.transfer ? Category.TRANSFER : decideCategory(leg);
            out.add(new NormalizedTxn(leg.accountLast4, leg.occurredAt, leg.direction,
                    leg.amount, category, leg.merchant, leg.sourceMessageIds));
            if (leg.statedBalance != null) {
                snapshots.add(new BalanceSnapshot(leg.accountLast4, leg.occurredAt, leg.statedBalance));
            }
        }
        out.sort(Comparator.comparing(NormalizedTxn::occurredAt)
                .thenComparing(NormalizedTxn::accountLast4)
                .thenComparing(t -> t.sourceMessageIds().get(0)));
        return new Assembled(out, snapshots);
    }

    public record Assembled(List<NormalizedTxn> transactions, List<BalanceSnapshot> balanceSnapshots) {}

    // ------------------------------------------------------------- merging

    private static List<Leg> merge(List<ParsedTxn> parsed) {
        Map<Key, List<ParsedTxn>> byKey = new LinkedHashMap<>();
        for (ParsedTxn p : parsed) {
            Key k = new Key(p.accountLast4(), p.direction(), p.amount(), p.occurredAt());
            byKey.computeIfAbsent(k, x -> new ArrayList<>()).add(p);
        }
        List<Leg> legs = new ArrayList<>();
        for (Map.Entry<Key, List<ParsedTxn>> e : byKey.entrySet()) {
            legs.add(Leg.of(e.getKey(), e.getValue()));
        }
        return legs;
    }

    // --------------------------------------------------------- categorising

    private static Category decideCategory(Leg leg) {
        if (leg.direction == Direction.DEBIT
                && leg.amount.compareTo(MICRO_THRESHOLD) <= 0
                && isUpi(leg.merchant)) {
            return Category.MICRO;
        }
        return leg.direction == Direction.DEBIT ? Category.SPEND : Category.INCOME;
    }

    private static boolean isUpi(String merchant) {
        // Most UPI merchant text is "UPI/<payee>", but bank-initiated UPI
        // events (mandate verification, autopay setup) show up as bare "UPI
        // MANDATE VERIFY" with no slash - still a UPI-channel debit.
        return merchant != null && merchant.trim().toUpperCase(Locale.ROOT).startsWith("UPI");
    }

    // ------------------------------------------------------ transfer pairing

    private static void pairTransfers(List<Leg> legs) {
        List<Leg> ordered = new ArrayList<>(legs);
        ordered.sort(Comparator.comparing((Leg l) -> l.occurredAt)
                .thenComparing(l -> l.accountLast4)
                .thenComparing(l -> l.sourceMessageIds.get(0)));

        for (int i = 0; i < ordered.size(); i++) {
            Leg a = ordered.get(i);
            if (a.transfer) continue;

            Leg best = null;
            Duration bestDelta = null;
            for (int j = 0; j < ordered.size(); j++) {
                if (i == j) continue;
                Leg b = ordered.get(j);
                if (b.transfer) continue;
                if (a.accountLast4.equals(b.accountLast4)) continue;
                if (a.direction == b.direction) continue;
                if (a.amount.compareTo(b.amount) != 0) continue;
                if (!counterpartySuffix(a.merchant).equals(counterpartySuffix(b.merchant))) continue;

                Duration delta = Duration.between(a.occurredAt, b.occurredAt).abs();
                if (delta.compareTo(TRANSFER_WINDOW) > 0) continue;

                if (best == null
                        || delta.compareTo(bestDelta) < 0
                        || (delta.equals(bestDelta)
                            && tieBreak(b).compareTo(tieBreak(best)) < 0)) {
                    best = b;
                    bestDelta = delta;
                }
            }
            if (best != null) {
                a.transfer = true;
                best.transfer = true;
            }
        }
    }

    private static String tieBreak(Leg l) {
        return l.accountLast4 + "|" + l.sourceMessageIds.get(0);
    }

    /** The counterparty name a transfer's two legs share: the text after the
     *  last "/" in the merchant field ("IMPS/P2A/PARAG KAPOOR" -&gt; "PARAG
     *  KAPOOR"), or the whole field if there is no "/". */
    private static String counterpartySuffix(String merchant) {
        if (merchant == null) return "";
        String m = merchant.trim().toUpperCase(Locale.ROOT);
        int idx = m.lastIndexOf('/');
        return idx >= 0 ? m.substring(idx + 1) : m;
    }

    // ------------------------------------------------------------- helpers

    private record Key(String accountLast4, Direction direction, BigDecimal amount,
                        OffsetDateTime occurredAt) {}

    private static final class Leg {
        final String accountLast4;
        final Direction direction;
        final BigDecimal amount;
        final OffsetDateTime occurredAt;
        final String merchant;
        final List<String> sourceMessageIds;
        final BigDecimal statedBalance;
        boolean transfer = false;

        private Leg(String accountLast4, Direction direction, BigDecimal amount,
                     OffsetDateTime occurredAt, String merchant, List<String> sourceMessageIds,
                     BigDecimal statedBalance) {
            this.accountLast4 = accountLast4;
            this.direction = direction;
            this.amount = amount;
            this.occurredAt = occurredAt;
            this.merchant = merchant;
            this.sourceMessageIds = sourceMessageIds;
            this.statedBalance = statedBalance;
        }

        static Leg of(Key key, List<ParsedTxn> evidence) {
            TreeSet<String> ids = new TreeSet<>();
            String merchant = null;
            BigDecimal statedBalance = null;
            for (ParsedTxn p : evidence) {
                ids.add(p.sourceMessageId());
                merchant = betterMerchant(merchant, p.merchant());
                if (p.statedBalance() != null) statedBalance = p.statedBalance();
            }
            return new Leg(key.accountLast4(), key.direction(), key.amount(), key.occurredAt(),
                    merchant, List.copyOf(ids), statedBalance);
        }

        /** Deterministic (order-independent) choice between two merchant
         *  strings seen for the same transaction: prefer the one that carries
         *  a channel prefix ("UPI/", "IMPS/P2A/"...), then the longer one,
         *  then the lexicographically smaller one. In practice every channel
         *  agrees, so this is a tie-break that should rarely bite. */
        static String betterMerchant(String a, String b) {
            if (a == null) return b;
            if (b == null) return a;
            if (a.equals(b)) return a;
            boolean aSlash = a.contains("/");
            boolean bSlash = b.contains("/");
            if (aSlash != bSlash) return aSlash ? a : b;
            if (a.length() != b.length()) return a.length() > b.length() ? a : b;
            return a.compareTo(b) <= 0 ? a : b;
        }
    }
}
