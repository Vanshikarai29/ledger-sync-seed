package in.simplifymoney.ledgersync.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import in.simplifymoney.ledgersync.parse.ParsedTxn;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class TransactionAssemblerTest {

    private static ParsedTxn p(String acct, String when, Direction dir, String amount,
                                String merchant, String msgId) {
        return p(acct, when, dir, amount, merchant, msgId, null);
    }

    private static ParsedTxn p(String acct, String when, Direction dir, String amount,
                                String merchant, String msgId, String balance) {
        return new ParsedTxn(acct, OffsetDateTime.parse(when), dir, new BigDecimal(amount),
                merchant, balance == null ? null : new BigDecimal(balance), msgId);
    }

    @Test
    void sameMessageUploadedTwiceCollapsesToOneTransaction() {
        // a device resync: identical underlying SMS, new message_id
        var evidence = List.of(
                p("4821", "2026-07-04T07:19:00+05:30", Direction.DEBIT, "5.00", "UPI/WATER CAN", "m-1"),
                p("4821", "2026-07-04T07:19:00+05:30", Direction.DEBIT, "5.00", "UPI/WATER CAN", "m-2"));

        List<NormalizedTxn> out = TransactionAssembler.assemble(evidence).transactions();

        assertEquals(1, out.size());
        assertEquals(List.of("m-1", "m-2"), out.get(0).sourceMessageIds());
    }

    @Test
    void sameEventReportedOnTwoChannelsCollapsesToOneTransaction() {
        // an SMS and the matching bank email for the same debit
        var evidence = List.of(
                p("4821", "2026-07-25T12:44:00+05:30", Direction.CREDIT, "1250.33", "UPI/P2P/REFUND", "sms-1"),
                p("4821", "2026-07-25T12:44:00+05:30", Direction.CREDIT, "1250.33", "UPI/P2P/REFUND", "email-1"));

        List<NormalizedTxn> out = TransactionAssembler.assemble(evidence).transactions();

        assertEquals(1, out.size());
        assertEquals(2, out.get(0).sourceMessageIds().size());
    }

    @Test
    void unrelatedTransactionsOnDifferentAccountsAreNotMerged() {
        var evidence = List.of(
                p("4821", "2026-07-04T07:19:00+05:30", Direction.DEBIT, "500.00", "AMAZON", "m-1"),
                p("9075", "2026-07-04T07:19:00+05:30", Direction.DEBIT, "500.00", "AMAZON", "m-2"));

        List<NormalizedTxn> out = TransactionAssembler.assemble(evidence).transactions();

        assertEquals(2, out.size());
    }

    @Test
    void upiDebitOfHundredOrLessIsMicro() {
        var evidence = List.of(
                p("4821", "2026-07-04T07:19:00+05:30", Direction.DEBIT, "5.00", "UPI/WATER CAN", "m-1"),
                p("4821", "2026-07-04T08:00:00+05:30", Direction.DEBIT, "100.00", "UPI/CHAIWALA", "m-2"));

        List<NormalizedTxn> out = TransactionAssembler.assemble(evidence).transactions();

        assertTrue(out.stream().allMatch(t -> t.category() == Category.MICRO));
    }

    @Test
    void upiMandateVerificationWithNoSlashIsStillMicro() {
        // "UPI MANDATE VERIFY" - no "/" - is still a UPI-channel debit
        var evidence = List.of(
                p("9075", "2026-07-26T10:05:00+05:30", Direction.DEBIT, "0.50", "UPI MANDATE VERIFY", "m-1"));

        List<NormalizedTxn> out = TransactionAssembler.assemble(evidence).transactions();

        assertEquals(Category.MICRO, out.get(0).category());
    }

    @Test
    void smallNonUpiDebitStaysSpendNotMicro() {
        // a card swipe under Rs.100 is not a UPI transaction - regular SPEND
        var evidence = List.of(
                p("3310", "2026-07-04T07:19:00+05:30", Direction.DEBIT, "47.33", "APOLLO PHARMACY", "m-1"));

        List<NormalizedTxn> out = TransactionAssembler.assemble(evidence).transactions();

        assertEquals(Category.SPEND, out.get(0).category());
    }

    @Test
    void matchingLegsOnTheTwoTrackedAccountsAreTransfers() {
        // a debit on 4821 and a credit on 9075, same amount, minutes apart,
        // same counterparty name - this is a self-transfer
        var evidence = List.of(
                p("4821", "2026-07-05T11:00:00+05:30", Direction.DEBIT, "8000.00", "IMPS/P2A/PARAG KAPOOR", "m-1"),
                p("9075", "2026-07-05T11:02:00+05:30", Direction.CREDIT, "8000.00", "IMPS/P2A/PARAG KAPOOR", "m-2"));

        List<NormalizedTxn> out = TransactionAssembler.assemble(evidence).transactions();

        assertEquals(2, out.size());
        assertTrue(out.stream().allMatch(t -> t.category() == Category.TRANSFER));
    }

    @Test
    void unmatchedSelfLabelledCreditStaysIncomeNotTransfer() {
        // "NEFT INWARD SELF" with no matching debit leg anywhere - we cannot
        // verify the other side is one of the two tracked accounts, so this
        // must not be assumed to be a transfer
        var evidence = List.of(
                p("9075", "2026-07-01T21:14:00+05:30", Direction.CREDIT, "18000.00", "NEFT INWARD SELF", "m-1"));

        List<NormalizedTxn> out = TransactionAssembler.assemble(evidence).transactions();

        assertEquals(Category.INCOME, out.get(0).category());
    }

    @Test
    void unmatchedTransferToAThirdPartyStaysSpendNotTransfer() {
        // a debit to a name that never shows up as a credit anywhere else -
        // a real payment to someone else, not a transfer between our accounts
        var evidence = List.of(
                p("4821", "2026-07-21T18:40:00+05:30", Direction.DEBIT, "12000.00", "IMPS/P2A/RAHUL SHARMA", "m-1"));

        List<NormalizedTxn> out = TransactionAssembler.assemble(evidence).transactions();

        assertEquals(Category.SPEND, out.get(0).category());
    }

    @Test
    void sameAmountSameCounterpartyButFarApartInTimeIsNotPaired() {
        // guards against a naive amount+name match with no time bound
        var evidence = List.of(
                p("4821", "2026-07-05T11:00:00+05:30", Direction.DEBIT, "8000.00", "IMPS/P2A/PARAG KAPOOR", "m-1"),
                p("9075", "2026-08-20T09:00:00+05:30", Direction.CREDIT, "8000.00", "IMPS/P2A/PARAG KAPOOR", "m-2"));

        List<NormalizedTxn> out = TransactionAssembler.assemble(evidence).transactions();

        assertTrue(out.stream().noneMatch(t -> t.category() == Category.TRANSFER));
    }
}
