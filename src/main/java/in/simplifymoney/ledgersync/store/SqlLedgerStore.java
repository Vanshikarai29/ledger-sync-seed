package in.simplifymoney.ledgersync.store;

import in.simplifymoney.ledgersync.ingest.BalanceSnapshot;
import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TreeSet;

/**
 * The store this service has used since it was written: a single relational
 * table, reached over plain JDBC.
 *
 * save() is idempotent by natural key (see LedgerStore) - implemented here as
 * a look-up before writing, not a schema-level UNIQUE constraint. The legacy
 * rows in db/migration/V2__seed.sql predate this guarantee and contain real
 * duplicates on purpose (see incident/, Backfill); adding a UNIQUE constraint
 * would make migrate() fail on that seed data.
 *
 * The driver is a runtime dependency (see build.gradle) - this class compiles
 * against the JDK alone.
 */
public final class SqlLedgerStore implements LedgerStore, AutoCloseable {

    private static final String URL_PREFIX = "jdbc:h2:";
    private final Connection conn;

    public SqlLedgerStore(Path dbFile) {
        try {
            this.conn = DriverManager.getConnection(
                    URL_PREFIX + dbFile.toAbsolutePath() + ";MODE=PostgreSQL", "sa", "");
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "could not open the ledger database at " + dbFile
                            + " (is the H2 driver on the runtime classpath?)", e);
        }
    }

    /** Applies every db/migration/V*.sql in filename order. */
    public void migrate(Path migrationDir) {
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS schema_history ("
                    + "  filename VARCHAR(200) PRIMARY KEY,"
                    + "  applied_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)");

            List<Path> files;
            try (var s = Files.list(migrationDir)) {
                files = s.filter(p -> p.getFileName().toString().endsWith(".sql")).sorted().toList();
            }
            for (Path f : files) {
                String name = f.getFileName().toString();
                try (PreparedStatement q = conn.prepareStatement(
                        "SELECT 1 FROM schema_history WHERE filename = ?")) {
                    q.setString(1, name);
                    try (ResultSet rs = q.executeQuery()) {
                        if (rs.next()) continue;
                    }
                }
                String sql = Files.readString(f);
                for (String stmt : sql.split(";")) {
                    if (!stmt.isBlank()) st.execute(stmt);
                }
                try (PreparedStatement ins = conn.prepareStatement(
                        "INSERT INTO schema_history(filename) VALUES (?)")) {
                    ins.setString(1, name);
                    ins.executeUpdate();
                }
                System.out.println("applied " + name);
            }
        } catch (Exception e) {
            throw new IllegalStateException("migration failed", e);
        }
    }

    @Override
    public void save(NormalizedTxn t) {
        try {
            List<String> existingIds = findSourceMessageIds(t);
            if (existingIds == null) {
                insert(t);
                return;
            }
            TreeSet<String> merged = new TreeSet<>(existingIds);
            merged.addAll(t.sourceMessageIds());
            if (merged.size() == existingIds.size()) return; // already up to date
            update(t, merged);
        } catch (SQLException e) {
            throw new IllegalStateException("could not save " + t, e);
        }
    }

    /** Null means no existing row for this natural key. */
    private List<String> findSourceMessageIds(NormalizedTxn t) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT source_message_ids FROM ledger WHERE account_last4 = ? AND"
                        + " direction = ? AND amount = ? AND occurred_at = ?")) {
            ps.setString(1, t.accountLast4());
            ps.setString(2, t.direction().name());
            ps.setBigDecimal(3, t.amount());
            ps.setString(4, t.occurredAt().toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return Arrays.stream(rs.getString(1).split(","))
                        .filter(s -> !s.isBlank()).toList();
            }
        }
    }

    private void insert(NormalizedTxn t) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO ledger(account_last4, occurred_at, direction, amount,"
                        + " category, merchant, source_message_ids)"
                        + " VALUES (?,?,?,?,?,?,?)")) {
            ps.setString(1, t.accountLast4());
            ps.setString(2, t.occurredAt().toString());
            ps.setString(3, t.direction().name());
            ps.setBigDecimal(4, t.amount());
            ps.setString(5, t.category().name());
            ps.setString(6, t.merchant());
            ps.setString(7, String.join(",", t.sourceMessageIds()));
            ps.executeUpdate();
        }
    }

    private void update(NormalizedTxn t, TreeSet<String> mergedIds) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE ledger SET source_message_ids = ?, category = ?, merchant = ?"
                        + " WHERE account_last4 = ? AND direction = ? AND amount = ?"
                        + " AND occurred_at = ?")) {
            ps.setString(1, String.join(",", mergedIds));
            ps.setString(2, t.category().name());
            ps.setString(3, t.merchant());
            ps.setString(4, t.accountLast4());
            ps.setString(5, t.direction().name());
            ps.setBigDecimal(6, t.amount());
            ps.setString(7, t.occurredAt().toString());
            ps.executeUpdate();
        }
    }

    @Override
    public List<NormalizedTxn> all() {
        List<NormalizedTxn> out = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT account_last4, occurred_at, direction, amount, category,"
                             + " merchant, source_message_ids FROM ledger ORDER BY occurred_at")) {
            while (rs.next()) {
                out.add(new NormalizedTxn(
                        rs.getString(1),
                        OffsetDateTime.parse(rs.getString(2)),
                        Direction.valueOf(rs.getString(3)),
                        rs.getBigDecimal(4).setScale(2),
                        Category.valueOf(rs.getString(5)),
                        rs.getString(6),
                        Arrays.stream(rs.getString(7).split(","))
                                .filter(s -> !s.isBlank()).toList()));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("could not read the ledger", e);
        }
        return out;
    }

    @Override
    public long count() {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM ledger")) {
            return rs.next() ? rs.getLong(1) : 0L;
        } catch (SQLException e) {
            throw new IllegalStateException("could not count the ledger", e);
        }
    }

    public BigDecimal sumAmounts() {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT SUM(amount) FROM ledger")) {
            return rs.next() && rs.getBigDecimal(1) != null
                    ? rs.getBigDecimal(1).setScale(2) : BigDecimal.ZERO.setScale(2);
        } catch (SQLException e) {
            throw new IllegalStateException("could not total the ledger", e);
        }
    }

    @Override
    public void close() {
        try { conn.close(); } catch (SQLException ignored) { }
    }

    @Override
    public void saveBalanceSnapshot(BalanceSnapshot s) {
        try (PreparedStatement ps = conn.prepareStatement(
                "MERGE INTO balance_snapshot(account_last4, occurred_at, balance) VALUES (?,?,?)")) {
            ps.setString(1, s.accountLast4());
            ps.setString(2, s.occurredAt().toString());
            ps.setBigDecimal(3, s.balance());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("could not save " + s, e);
        }
    }

    @Override
    public List<BalanceSnapshot> balanceSnapshots() {
        List<BalanceSnapshot> out = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT account_last4, occurred_at, balance FROM balance_snapshot"
                             + " ORDER BY account_last4, occurred_at")) {
            while (rs.next()) {
                out.add(new BalanceSnapshot(rs.getString(1),
                        OffsetDateTime.parse(rs.getString(2)), rs.getBigDecimal(3).setScale(2)));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("could not read balance snapshots", e);
        }
        return out;
    }
}
