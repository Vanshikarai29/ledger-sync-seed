package in.simplifymoney.ledgersync.store;

import in.simplifymoney.ledgersync.ingest.TransactionAssembler;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import in.simplifymoney.ledgersync.parse.ParsedTxn;
import java.util.ArrayList;
import java.util.List;

/**
 * Moves everything already in the SQL store into the document store.
 *
 * The SQL table is not one row per real transaction - some of it was written
 * directly by db/migration/V2__seed.sql with no uniqueness guarantee at all,
 * so the same real transaction can appear as several literal rows: exact
 * repeats (a retried write that was never idempotent) and genuine
 * multi-message evidence (the same transaction reported on two channels,
 * stored as two separate rows because the pipeline that wrote them predates
 * TransactionAssembler). Backfill treats every SQL row as one piece of
 * evidence and feeds all of it through TransactionAssembler exactly as
 * IngestService does for a fresh corpus - so a legacy transaction gets the
 * same merge-by-natural-key treatment AND the same category logic (including
 * transfer pairing) as anything ingested today. That second part matters: the
 * legacy IMPS/P2A/PARAG KAPOOR pair in V2__seed.sql was written as SPEND and
 * INCOME by a pipeline that had no transfer detection - Backfill correctly
 * re-derives it as TRANSFER instead of copying the old, wrong categorisation
 * forward.
 *
 * What Backfill deliberately does NOT do: correct a wrong amount. One legacy
 * row (m-legacy-0041, the water-can row from INC-2026-09-11) has an amount we
 * know is wrong - but fixing it would mean inventing a number, because the
 * legacy table does not retain the original message body to re-parse. Moving
 * it through as-is, faithfully, is more honest than a Backfill run that
 * silently "corrects" financial history from nothing. It comes through
 * tagged the same way any other row does; recovering the true amount is a
 * manual follow-up once the original SMS can be found, not something this
 * migration can do on its own.
 *
 * Idempotent by construction: DocumentStore.save() is idempotent by natural
 * key (see LedgerStore/DocumentStore), so running this twice, or resuming
 * after a crash partway through the target.save() loop, re-applies the exact
 * same final state - it does not matter how far a previous run got. read/
 * written/skipped are recomputed fresh from the CURRENT contents of the SQL
 * table on every call, not accumulated across calls, so they stay correct
 * (and identical) however many times this runs.
 */
public final class Backfill {

    private final SqlLedgerStore source;
    private final DocumentStore target;

    public Backfill(SqlLedgerStore source, DocumentStore target) {
        this.source = source;
        this.target = target;
    }

    public Result run() {
        List<NormalizedTxn> rawRows = source.all();

        List<ParsedTxn> evidence = new ArrayList<>();
        for (NormalizedTxn row : rawRows) {
            for (String messageId : row.sourceMessageIds()) {
                evidence.add(new ParsedTxn(row.accountLast4(), row.occurredAt(), row.direction(),
                        row.amount(), row.merchant(), null, messageId));
            }
        }

        List<NormalizedTxn> deduped = TransactionAssembler.assemble(evidence).transactions();
        for (NormalizedTxn t : deduped) {
            target.save(t);
        }

        long read = rawRows.size();
        long written = deduped.size();
        long skipped = read - written; // exact repeats + rows merged into another row's evidence
        return new Result(read, written, skipped);
    }

    /**
     * read      every row in the SQL table, including exact duplicates
     * written   distinct real transactions found - each saved exactly once,
     *           idempotently, to the document store
     * skipped   rows that did not add a new transaction: exact repeats of a
     *           row already counted, or a second/third piece of evidence for
     *           a transaction another row already established
     */
    public record Result(long read, long written, long skipped) {}
}
