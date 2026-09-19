package in.simplifymoney.ledgersync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.RawMessage;
import in.simplifymoney.ledgersync.parse.ParsedTxn;
import in.simplifymoney.ledgersync.parse.Parsers;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * INC-2026-09-11 — reproduced end to end.
 *
 * The message below is the exact SMS behind the customer's complaint (see
 * incident/app.log, msg=m-00004-9c11ae). Before the fix, Parsers.parse()
 * returns a ParsedTxn with amount=92213.10 (the quoted available balance)
 * instead of amount=5.00 (what the bank actually says was debited). The root
 * cause is in Amounts.first(), covered directly by AmountsTest; this test
 * pins the behaviour at the level support actually sees it - one message in,
 * one parsed transaction out.
 */
class IncidentINC20260911Test {

    @Test
    void theWaterCanDebitIsReadAsFiveRupeesNotTheBalance() {
        RawMessage m = new RawMessage(
                "m-00004-9c11ae",
                "sms",
                "AD-HDFCBK-S",
                OffsetDateTime.parse("2026-07-04T07:19:00+05:30"),
                "dev-3f1a90c47b21",
                "Rs.5 debited from a/c **4821 on 04-07-26 at 07:19 to UPI/WATER CAN. "
                        + "Avl Bal: Rs.92,213.10. Not you? Call 18002586161");

        Optional<ParsedTxn> parsed = new Parsers().parse(m);

        assertTrue(parsed.isPresent(), "the message is a real transaction and must parse");
        ParsedTxn t = parsed.get();
        assertEquals(new BigDecimal("5.00"), t.amount(),
                "the customer was debited Rs.5, not their Rs.92,213.10 balance");
        assertEquals("4821", t.accountLast4());
        assertEquals(Direction.DEBIT, t.direction());
        assertEquals(new BigDecimal("92213.10"), t.statedBalance(),
                "the balance is still captured - just not confused with the amount");
    }
}
