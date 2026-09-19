package in.simplifymoney.ledgersync;

import in.simplifymoney.ledgersync.ingest.IngestService;
import in.simplifymoney.ledgersync.json.Json;
import in.simplifymoney.ledgersync.parse.Parsers;
import in.simplifymoney.ledgersync.report.Reports;
import in.simplifymoney.ledgersync.store.Backfill;
import in.simplifymoney.ledgersync.store.ConsistencyChecker;
import in.simplifymoney.ledgersync.store.DocumentStore;
import in.simplifymoney.ledgersync.store.SqlLedgerStore;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Command line entry point.
 *
 *   migrate                  apply db/migration/*.sql
 *   ingest  <corpus.jsonl>   read a corpus into the SQL ledger
 *   report  <out-dir>        write ledger.json, summary.json, reconciliation.json from SQL
 *   backfill                 move everything in SQL into the document store (docker compose up first)
 *   check                    compare SQL and the document store, print any divergences
 *   bench                    seed 100,000 synthetic transactions and print examined/returned for Q1-Q3
 *
 * migrate/ingest/report are the pipeline built in Task 2 - still here,
 * still SQL-backed, since it is what verify.sh exercises and it is fully
 * tested. backfill/check/bench are Task 4: moving that same ledger onto
 * MongoDB. Both point at the same SQL database (data/ledger), so `ingest`
 * then `backfill` is exactly the migration this task describes.
 *
 * openDocumentStore() below reaches MongoDocumentStore through reflection
 * rather than importing it directly, on purpose: everything else in this
 * class - and everything verify.sh compiles - builds against the JDK alone,
 * and a direct import would force the Mongo driver onto that build too (see
 * verify.sh's comment on why store/mongo is excluded from it). The commands
 * that actually need it (backfill/check/bench) still fail loudly and
 * immediately if the driver truly is not on the classpath - this only
 * changes what the *compiler* needs to see.
 */
public final class App {

    private static final Path DB = Path.of("data", "ledger");
    private static final Path MIGRATIONS = Path.of("db", "migration");
    private static final String MONGO_URL =
            System.getenv().getOrDefault("MONGO_URL", "mongodb://localhost:27017/?replicaSet=rs0&directConnection=true");

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("usage: migrate | ingest <corpus.jsonl> | report <out-dir> "
                    + "| backfill | check | bench");
            System.exit(2);
        }
        Files.createDirectories(DB.getParent());

        switch (args[0]) {
            case "migrate" -> {
                try (SqlLedgerStore store = new SqlLedgerStore(DB)) {
                    store.migrate(MIGRATIONS);
                    System.out.println("ledger rows: " + store.count());
                }
            }
            case "ingest" -> {
                if (args.length < 2) throw new IllegalArgumentException("ingest needs a corpus");
                try (SqlLedgerStore store = new SqlLedgerStore(DB)) {
                    store.migrate(MIGRATIONS);
                    var stats = new IngestService(new Parsers(), store)
                            .ingestFile(Path.of(args[1]));
                    System.out.println(stats);
                    System.out.println("ledger rows: " + store.count());
                }
            }
            case "report" -> {
                if (args.length < 2) throw new IllegalArgumentException("report needs a directory");
                Path out = Path.of(args[1]);
                Files.createDirectories(out);
                try (SqlLedgerStore store = new SqlLedgerStore(DB)) {
                    var ledger = store.all();
                    Files.writeString(out.resolve("ledger.json"),
                            Json.writePretty(Reports.ledgerDocument(ledger)));
                    Files.writeString(out.resolve("summary.json"),
                            Json.writePretty(Reports.summary(ledger)));
                    Files.writeString(out.resolve("reconciliation.json"),
                            Json.writePretty(Reports.reconciliation(ledger, store.balanceSnapshots())));
                    System.out.println("wrote 3 files to " + out);
                }
            }
            case "backfill" -> {
                try (SqlLedgerStore sql = new SqlLedgerStore(DB);
                     DocumentStore docs = openDocumentStore()) {
                    sql.migrate(MIGRATIONS);
                    var result = new Backfill(sql, docs).run();
                    System.out.println(result);
                }
            }
            case "check" -> {
                try (SqlLedgerStore sql = new SqlLedgerStore(DB);
                     DocumentStore docs = openDocumentStore()) {
                    sql.migrate(MIGRATIONS);
                    var divergences = new ConsistencyChecker(sql, docs).check();
                    if (divergences.isEmpty()) {
                        System.out.println("no divergences - the two stores agree");
                    } else {
                        System.out.println(divergences.size() + " divergence(s):");
                        divergences.forEach(d -> System.out.println("  " + d));
                    }
                }
            }
            case "bench" -> {
                try (DocumentStore docs = openDocumentStore()) {
                    Bench.run(docs);
                }
            }
            default -> {
                System.err.println("unknown command: " + args[0]);
                System.exit(2);
            }
        }
    }

    private static DocumentStore openDocumentStore() throws Exception {
        try {
            Class<?> cls = Class.forName("in.simplifymoney.ledgersync.store.mongo.MongoDocumentStore");
            return (DocumentStore) cls.getConstructor(String.class).newInstance(MONGO_URL);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("MongoDocumentStore is not on the classpath - "
                    + "run this command through gradle, which pulls in the driver "
                    + "(build.gradle: org.mongodb:mongodb-driver-sync), not via verify.sh's plain javac", e);
        }
    }
}
