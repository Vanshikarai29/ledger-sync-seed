package in.simplifymoney.ledgersync.parse;

import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.RawMessage;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ICICI Bank SMS.
 *
 * Two shapes are in production, the same way HDFC has an older and a newer
 * one:
 *
 *   V1  "Dear Customer, Acct XX9075 is debited with INR 22.50 on
 *        01/07/2026 10:22. Info: UPI/VEGETABLE VENDOR. Avl Bal Rs.31,882.25
 *        -ICICI Bank"
 *
 *   V2  "ICICI Bank Acct XX9075 Dr INR 5 on 23-Jul-2026 18:41;
 *        UPI/BARBER ref no 154245459403. BalAvl Rs 52,841.30"
 *
 * Marketing SMS from the same sender ("Get a pre-approved Personal Loan...")
 * match neither shape and are correctly left unparsed.
 */
public final class IciciSmsParser implements MessageParser {

    public static final String SENDER = "VM-ICICIB-T";

    private static final Pattern V1 = Pattern.compile(
            "Acct XX(?<acct>\\d{4}) is (?<dir>debited|credited) with .*? "
                    + "on (?<when>\\d{2}/\\d{2}/\\d{4} \\d{2}:\\d{2})\\. "
                    + "Info: (?<merchant>[^.]+)\\.");

    private static final Pattern V2 = Pattern.compile(
            "ICICI Bank Acct XX(?<acct>\\d{4}) (?<dir>Cr|Dr) INR [\\d,.]+ "
                    + "on (?<when>\\d{2}-\\w{3}-\\d{4} \\d{2}:\\d{2}); "
                    + "(?<merchant>.+?) ref no \\d+\\. BalAvl Rs");

    @Override
    public boolean supports(RawMessage m) {
        return "sms".equals(m.channel()) && SENDER.equals(m.sender());
    }

    @Override
    public Optional<ParsedTxn> parse(RawMessage m) {
        String body = m.body();

        Matcher v1 = V1.matcher(body);
        if (v1.find()) {
            Direction d = "debited".equals(v1.group("dir")) ? Direction.DEBIT : Direction.CREDIT;
            return build(m, v1.group("acct"), v1.group("when"), d, v1.group("merchant"));
        }

        Matcher v2 = V2.matcher(body);
        if (v2.find()) {
            Direction d = "Dr".equals(v2.group("dir")) ? Direction.DEBIT : Direction.CREDIT;
            return build(m, v2.group("acct"), v2.group("when"), d, v2.group("merchant"));
        }

        return Optional.empty();
    }

    private Optional<ParsedTxn> build(RawMessage m, String acct, String when,
                                       Direction dir, String merchant) {
        BigDecimal amount = Amounts.first(m.body());
        OffsetDateTime at = Dates.ist(when);
        if (amount == null || at == null) return Optional.empty();
        return Optional.of(new ParsedTxn(acct, at, dir, amount, merchant.trim(),
                Amounts.statedBalance(m.body()), m.messageId()));
    }
}
