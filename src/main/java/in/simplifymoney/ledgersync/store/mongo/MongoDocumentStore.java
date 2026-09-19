package in.simplifymoney.ledgersync.store.mongo;

import com.mongodb.ExplainVerbosity;
import com.mongodb.client.ClientSession;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.ReturnDocument;
import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import in.simplifymoney.ledgersync.store.DocumentStore;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.bson.Document;
import org.bson.types.Decimal128;

/**
 * MongoDB, chosen over DynamoDB - see the README decision log for the full
 * reasoning; in short: a single collection design serves all three queries
 * with plain compound/multikey indexes and no secondary-index read
 * amplification to reason about, mongosh's explain() gives the exact
 * examined/returned numbers the assignment asks for with no extra
 * instrumentation, and a local single-node replica set (for the transaction
 * in save(), below) comes up from one docker-compose service with no
 * DynamoDB Local table-provisioning boilerplate.
 *
 * Two collections:
 *
 *   transactions               one document per NormalizedTxn. _id is the
 *     natural key (accountLast4|direction|amount|occurredAt) as a string, so
 *     save() is a plain upsert - no separate existence check needed, and no
 *     duplicate can ever be created even under concurrent/repeated calls.
 *     Indexes: {account_last4, year_month, occurred_at desc} for Q1;
 *     {source_message_ids} (multikey - Mongo indexes each array element) for
 *     Q3. year_month is precomputed and stored redundantly specifically so Q1
 *     is a single equality+equality+sort index scan with no in-memory date
 *     filtering.
 *
 *   account_category_totals    one document per account, _id = accountLast4,
 *     holding a running Decimal128 total per Category. This is the
 *     "materialized view" choice for Q2: summing a whole account's history on
 *     every read does not scale, so save() keeps a running total up to date
 *     instead, and Q2 becomes a single point read by _id (examined=1,
 *     returned=1) regardless of how many transactions the account has.
 *
 * save() touches both collections and must not double-count a transaction it
 * has already seen (IngestService and Backfill can both call it more than
 * once for the same natural key). It runs both writes in a session
 * transaction: findOneAndUpdate on transactions with returnDocument(BEFORE)
 * atomically tells us in one round trip both whether this was a fresh insert
 * and, if not, what the old category was - so the totals increment (or
 * category-change correction) and the transaction upsert commit or roll back
 * together. This needs mongod running as a (single-node is fine) replica
 * set - see docker-compose.yml.
 */
public final class MongoDocumentStore implements DocumentStore {

    private final MongoClient client;
    private final MongoCollection<Document> transactions;
    private final MongoCollection<Document> totals;

    public MongoDocumentStore(String connectionString) {
        this.client = MongoClients.create(connectionString);
        MongoDatabase db = client.getDatabase("ledgersync");
        this.transactions = db.getCollection("transactions");
        this.totals = db.getCollection("account_category_totals");
        ensureIndexes();
    }

    private void ensureIndexes() {
        transactions.createIndex(new Document("account_last4", 1)
                .append("year_month", 1)
                .append("occurred_at", -1));
        transactions.createIndex(new Document("source_message_ids", 1));
    }

    private static String naturalKey(NormalizedTxn t) {
        return t.accountLast4() + "|" + t.direction() + "|"
                + t.amount().toPlainString() + "|" + t.occurredAt();
    }

    // ------------------------------------------------------------- Q1

    @Override
    public List<NormalizedTxn> forAccountMonth(String accountLast4, YearMonth month) {
        FindIterable<Document> it = transactions.find(new Document("account_last4", accountLast4)
                        .append("year_month", month.toString()))
                .sort(new Document("occurred_at", -1));
        List<NormalizedTxn> out = new ArrayList<>();
        for (Document d : it) out.add(fromDoc(d));
        return out;
    }

    // ------------------------------------------------------------- Q2

    @Override
    public Map<Category, BigDecimal> categoryTotals(String accountLast4) {
        Document d = totals.find(new Document("_id", accountLast4)).first();
        Map<Category, BigDecimal> out = new LinkedHashMap<>();
        for (Category c : Category.values()) out.put(c, BigDecimal.ZERO.setScale(2));
        if (d == null) return out;
        Document t = d.get("totals", Document.class);
        if (t == null) return out;
        for (Category c : Category.values()) {
            Decimal128 v = t.get(c.name(), Decimal128.class);
            if (v != null) out.put(c, v.bigDecimalValue().setScale(2));
        }
        return out;
    }

    // ------------------------------------------------------------- Q3

    @Override
    public Optional<NormalizedTxn> byMessageId(String messageId) {
        Document d = transactions.find(new Document("source_message_ids", messageId)).first();
        return Optional.ofNullable(d).map(MongoDocumentStore::fromDoc);
    }

    // ------------------------------------------------------------- save

    @Override
    public void save(NormalizedTxn t) {
        String id = naturalKey(t);
        String yearMonth = YearMonth.from(t.occurredAt()).toString();
        Decimal128 amount = new Decimal128(t.amount());

        Document update = new Document()
                .append("$setOnInsert", new Document("account_last4", t.accountLast4())
                        .append("direction", t.direction().name())
                        .append("amount", amount)
                        .append("occurred_at", t.occurredAt().toString())
                        .append("year_month", yearMonth))
                .append("$set", new Document("category", t.category().name())
                        .append("merchant", t.merchant()))
                .append("$addToSet", new Document("source_message_ids",
                        new Document("$each", t.sourceMessageIds())));

        try (ClientSession session = client.startSession()) {
            session.withTransaction(() -> {
                Document before = transactions.findOneAndUpdate(session,
                        new Document("_id", id), update,
                        new FindOneAndUpdateOptions().upsert(true).returnDocument(ReturnDocument.BEFORE));

                if (before == null) {
                    // fresh transaction: add its amount to its category's running total
                    incTotal(session, t.accountLast4(), t.category(), t.amount());
                } else {
                    Category oldCategory = Category.valueOf(before.getString("category"));
                    if (oldCategory != t.category()) {
                        // a later ingest re-categorised this transaction (e.g. a
                        // transfer leg's counterpart showed up in a later batch)
                        // - move its amount between the two running totals
                        incTotal(session, t.accountLast4(), oldCategory, t.amount().negate());
                        incTotal(session, t.accountLast4(), t.category(), t.amount());
                    }
                    // else: same category, or only new source_message_ids merged in -
                    // no change in what this transaction is worth, so no totals change
                }
                return null;
            });
        }
    }

    private void incTotal(ClientSession session, String accountLast4, Category category, BigDecimal delta) {
        totals.updateOne(session, new Document("_id", accountLast4),
                new Document("$inc", new Document("totals." + category.name(), new Decimal128(delta)))
                        .append("$setOnInsert", new Document("_id", accountLast4)),
                new com.mongodb.client.model.UpdateOptions().upsert(true));
    }

    // ------------------------------------------------------- migration tooling

    @Override
    public List<NormalizedTxn> allForMigrationTooling() {
        List<NormalizedTxn> out = new ArrayList<>();
        for (Document d : transactions.find()) out.add(fromDoc(d));
        return out;
    }

    // ------------------------------------------------------- explain (bench)

    @Override
    public int[] examineForAccountMonth(String accountLast4, YearMonth month) {
        Document explain = transactions.find(new Document("account_last4", accountLast4)
                        .append("year_month", month.toString()))
                .sort(new Document("occurred_at", -1))
                .explain(ExplainVerbosity.EXECUTION_STATS);
        return examinedAndReturned(explain);
    }

    @Override
    public int[] examineForCategoryTotals(String accountLast4) {
        Document explain = totals.find(new Document("_id", accountLast4))
                .explain(ExplainVerbosity.EXECUTION_STATS);
        return examinedAndReturned(explain);
    }

    @Override
    public int[] examineForMessageId(String messageId) {
        Document explain = transactions.find(new Document("source_message_ids", messageId))
                .explain(ExplainVerbosity.EXECUTION_STATS);
        return examinedAndReturned(explain);
    }

    private static int[] examinedAndReturned(Document explainOutput) {
        Document stats = explainOutput.get("executionStats", Document.class);
        return new int[] {stats.getInteger("totalDocsExamined"), stats.getInteger("nReturned")};
    }

    // ------------------------------------------------------------- mapping

    private static NormalizedTxn fromDoc(Document d) {
        Decimal128 amount = d.get("amount", Decimal128.class);
        @SuppressWarnings("unchecked")
        List<String> ids = (List<String>) (List<?>) d.getList("source_message_ids", String.class);
        return new NormalizedTxn(
                d.getString("account_last4"),
                OffsetDateTime.parse(d.getString("occurred_at")),
                Direction.valueOf(d.getString("direction")),
                amount.bigDecimalValue().setScale(2),
                Category.valueOf(d.getString("category")),
                d.getString("merchant"),
                List.copyOf(ids));
    }

    @Override
    public void close() {
        client.close();
    }
}
