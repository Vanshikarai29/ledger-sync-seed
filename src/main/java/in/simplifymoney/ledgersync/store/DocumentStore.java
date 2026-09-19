package in.simplifymoney.ledgersync.store;

import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The document store the ledger is moving to. NOT IMPLEMENTED - this is yours.
 *
 * These three methods are the only access patterns this service has. Design
 * your documents so the engine can serve them directly. We are not going to
 * tell you what the documents should look like; that decision is the point of
 * the exercise.
 *
 * For each method your submission must report, at 100,000 transactions, how
 * many items the engine EXAMINED versus how many it RETURNED. Both DynamoDB
 * (ScannedCount vs Count) and MongoDB (totalDocsExamined vs nReturned) give you
 * this directly. Put the numbers in your README.
 */
public interface DocumentStore extends AutoCloseable {

    /** Q1: one account's transactions for one month, newest first. */
    List<NormalizedTxn> forAccountMonth(String accountLast4, YearMonth month);

    /** Q2: running totals per category for an account, for its whole history. */
    Map<Category, BigDecimal> categoryTotals(String accountLast4);

    /** Q3: which transaction, if any, did this message produce? */
    Optional<NormalizedTxn> byMessageId(String messageId);

    /**
     * Idempotent by natural key - same contract as LedgerStore.save(): saving
     * a transaction that already exists under (accountLast4, direction,
     * amount, occurredAt) merges sourceMessageIds instead of duplicating the
     * row, and does not double-count it in categoryTotals().
     */
    void save(NormalizedTxn txn);

    /**
     * Every transaction in the store, unindexed and unpaginated.
     *
     * NOT one of the three queries above - the running service never calls
     * this. It exists only for Backfill and ConsistencyChecker, which need to
     * see everything to compare the two stores. A full collection scan is the
     * right cost model for "compare everything to everything, once, during a
     * migration"; it would be the wrong one for a request path.
     */
    List<NormalizedTxn> allForMigrationTooling();

    /**
     * {itemsExamined, itemsReturned} for each of the three queries above -
     * what DynamoDB reports as {ScannedCount, Count} and MongoDB as
     * {totalDocsExamined, nReturned}. Also not one of the three production
     * queries; used only by the `bench` command to produce the README's six
     * numbers, by actually running each query at scale and reading back
     * whatever the store reports rather than guessing.
     */
    int[] examineForAccountMonth(String accountLast4, YearMonth month);

    int[] examineForCategoryTotals(String accountLast4);

    int[] examineForMessageId(String messageId);
}
