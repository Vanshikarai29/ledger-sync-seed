package in.simplifymoney.ledgersync.store;

import java.util.List;

/**
 * Proves the two stores agree, and says precisely where they do not.
 *
 * NOT IMPLEMENTED - this is yours.
 *
 * We will run your checker against a document store we have deliberately
 * altered. It has to find what we changed and name it. A checker that only
 * compares row counts will not.
 */
public final class ConsistencyChecker {

    private final SqlLedgerStore sql;
    private final DocumentStore documents;

    public ConsistencyChecker(SqlLedgerStore sql, DocumentStore documents) {
        this.sql = sql;
        this.documents = documents;
    }

    public List<Divergence> check() {
        throw new UnsupportedOperationException("consistency checking is not implemented");
    }

    /** One place the two stores disagree. */
    public record Divergence(String what, String inSql, String inDocuments) {}
}
