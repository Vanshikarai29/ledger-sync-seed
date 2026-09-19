package in.simplifymoney.ledgersync.parse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.RawMessage;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class IciciSmsParserTest {

    private final IciciSmsParser parser = new IciciSmsParser();

    private static RawMessage sms(String body) {
        return new RawMessage("m-1", "sms", IciciSmsParser.SENDER,
                OffsetDateTime.parse("2026-07-01T21:14:00+05:30"), "dev-1", body);
    }

    @Test
    void readsTheV1DearCustomerShape() {
        Optional<ParsedTxn> p = parser.parse(sms(
                "Dear Customer, Acct XX9075 is debited with INR 22.50 on 01/07/2026 10:22. "
                        + "Info: UPI/VEGETABLE VENDOR. Avl Bal Rs.31,882.25 -ICICI Bank"));

        assertTrue(p.isPresent());
        assertEquals("9075", p.get().accountLast4());
        assertEquals(Direction.DEBIT, p.get().direction());
        assertEquals(new BigDecimal("22.50"), p.get().amount());
        assertEquals("UPI/VEGETABLE VENDOR", p.get().merchant());
    }

    @Test
    void readsTheV2SemicolonShape() {
        Optional<ParsedTxn> p = parser.parse(sms(
                "ICICI Bank Acct XX9075 Dr INR 5 on 23-Jul-2026 18:41; "
                        + "UPI/BARBER ref no 154245459403. BalAvl Rs 52,841.30"));

        assertTrue(p.isPresent());
        assertEquals("9075", p.get().accountLast4());
        assertEquals(Direction.DEBIT, p.get().direction());
        assertEquals(new BigDecimal("5.00"), p.get().amount(),
                "a whole-rupee V2 amount must not be confused with the balance");
        assertEquals("UPI/BARBER", p.get().merchant());
    }

    @Test
    void readsAV2Credit() {
        Optional<ParsedTxn> p = parser.parse(sms(
                "ICICI Bank Acct XX9075 Cr INR 1250.33 on 23-Jul-2026 16:52; "
                        + "INTEREST CREDIT ref no 424353460512. BalAvl Rs 52,846.30"));

        assertTrue(p.isPresent());
        assertEquals(Direction.CREDIT, p.get().direction());
        assertEquals(new BigDecimal("1250.33"), p.get().amount());
    }

    @Test
    void marketingSmsFromTheSameSenderIsLeftUnparsed() {
        Optional<ParsedTxn> p = parser.parse(sms(
                "Get a pre-approved Personal Loan of upto Rs.5,00,000 at 10.5% p.a. "
                        + "Click to know more. T&C apply. -ICICI Bank"));

        assertTrue(p.isEmpty());
    }
}
