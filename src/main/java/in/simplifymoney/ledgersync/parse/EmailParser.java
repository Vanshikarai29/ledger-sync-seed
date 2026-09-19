package in.simplifymoney.ledgersync.parse;

import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.RawMessage;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bank transaction alert emails.
 *
 * Both banks send the same shape:
 *
 *   Date: Wed, 01 Jul 2026 09:02:00 +0530
 *   Subject: Transaction alert on your account
 *
 *   Dear Customer,
 *
 *   Your account ending 4821 has been credited with INR 45,000.
 *   Merchant / Remarks: SALARY CREDIT
 *   Transaction reference: 1597155421
 *
 *   This is a system generated email.
 *
 * These emails carry no separate "when the transaction happened" field - the
 * Date header IS the transaction time (the bank sends the alert the same
 * minute the transaction posts; this is confirmed by cross-referencing the
 * SMS alert for the same event, which always agrees with the email's Date
 * header to the minute). There is no balance quoted in this format, so
 * statedBalance is always null for mail - that is expected, not a bug.
 */
public final class EmailParser implements MessageParser {

    private static final Pattern BODY = Pattern.compile(
            "Your account ending (?<acct>\\d{4}) has been "
                    + "(?<dir>debited|credited) with (?:Rs\\.?|INR)\\s*[\\d,.]+\\.\\n"
                    + "Merchant / Remarks: (?<merchant>.+)");

    private static final Pattern DATE_HEADER = Pattern.compile("^Date: (?<date>.+)$", Pattern.MULTILINE);

    @Override
    public boolean supports(RawMessage m) {
        return "email".equals(m.channel());
    }

    @Override
    public Optional<ParsedTxn> parse(RawMessage m) {
        String body = m.body();

        Matcher b = BODY.matcher(body);
        if (!b.find()) return Optional.empty();

        Matcher d = DATE_HEADER.matcher(body);
        if (!d.find()) return Optional.empty();

        OffsetDateTime at;
        try {
            at = Dates.toIst(OffsetDateTime.parse(d.group("date").trim(), DateTimeFormatter.RFC_1123_DATE_TIME));
        } catch (Exception e) {
            return Optional.empty();
        }

        BigDecimal amount = Amounts.first(body);
        if (amount == null) return Optional.empty();

        Direction dir = "debited".equals(b.group("dir")) ? Direction.DEBIT : Direction.CREDIT;
        return Optional.of(new ParsedTxn(b.group("acct"), at, dir, amount,
                b.group("merchant").trim(), Amounts.statedBalance(body), m.messageId()));
    }
}
