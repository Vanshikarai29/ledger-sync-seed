package in.simplifymoney.ledgersync.parse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.RawMessage;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class EmailParserTest {

    private final EmailParser parser = new EmailParser();

    private static RawMessage email(String body) {
        return new RawMessage("m-1", "email", "alerts@hdfcbank.net",
                OffsetDateTime.parse("2026-07-01T09:02:00+05:30"), "dev-1", body);
    }

    @Test
    void readsAStandardDebitAlert() {
        Optional<ParsedTxn> p = parser.parse(email(
                "Date: Wed, 01 Jul 2026 09:02:00 +0530\n"
                        + "Subject: Transaction alert on your account\n\n"
                        + "Dear Customer,\n\n"
                        + "Your account ending 4821 has been debited with Rs.649.00.\n"
                        + "Merchant / Remarks: NETFLIX ENTERTAINMENT\n"
                        + "Transaction reference: 9201732154\n\n"
                        + "This is a system generated email."));

        assertTrue(p.isPresent());
        assertEquals("4821", p.get().accountLast4());
        assertEquals(Direction.DEBIT, p.get().direction());
        assertEquals(new BigDecimal("649.00"), p.get().amount());
        assertEquals("NETFLIX ENTERTAINMENT", p.get().merchant());
        assertEquals(OffsetDateTime.parse("2026-07-01T09:02:00+05:30"), p.get().occurredAt());
    }

    @Test
    void aWholeRupeeAmountIsNotConfusedWithAnything() {
        // regression for the same class of bug as INC-2026-09-11: a
        // whole-rupee amount ("INR 45,000." - no paise) must still read as
        // the amount, not fail to match or grab something else
        Optional<ParsedTxn> p = parser.parse(email(
                "Date: Wed, 01 Jul 2026 09:02:00 +0530\n"
                        + "Subject: Transaction alert on your account\n\n"
                        + "Dear Customer,\n\n"
                        + "Your account ending 4821 has been credited with INR 45,000.\n"
                        + "Merchant / Remarks: SALARY CREDIT\n"
                        + "Transaction reference: 1597155421\n\n"
                        + "This is a system generated email."));

        assertTrue(p.isPresent());
        assertEquals(new BigDecimal("45000.00"), p.get().amount());
    }

    @Test
    void aUtcOffsetDateHeaderIsNormalisedToIst() {
        // exactly one email in the real corpus uses +0000 instead of +0530;
        // occurred_at must always come out in IST regardless
        Optional<ParsedTxn> p = parser.parse(email(
                "Date: Sat, 18 Jul 2026 18:50:00 +0000\n"
                        + "Subject: Transaction alert on your account\n\n"
                        + "Dear Customer,\n\n"
                        + "Your account ending 4821 has been debited with INR 412.67.\n"
                        + "Merchant / Remarks: UBER INDIA\n"
                        + "Transaction reference: 4190129089\n\n"
                        + "This is a system generated email."));

        assertTrue(p.isPresent());
        assertEquals(OffsetDateTime.parse("2026-07-19T00:20:00+05:30"), p.get().occurredAt());
    }

    @Test
    void nonTransactionEmailsAreLeftUnparsed() {
        Optional<ParsedTxn> p = parser.parse(email(
                "Date: Wed, 01 Jul 2026 09:02:00 +0530\n"
                        + "Subject: Your monthly statement is ready\n\n"
                        + "Dear Customer, your statement for June is now available."));

        assertTrue(p.isEmpty());
    }
}
