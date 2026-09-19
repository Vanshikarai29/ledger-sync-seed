package in.simplifymoney.ledgersync.ingest;

import in.simplifymoney.ledgersync.json.Json;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import in.simplifymoney.ledgersync.model.RawMessage;
import in.simplifymoney.ledgersync.parse.ParsedTxn;
import in.simplifymoney.ledgersync.parse.Parsers;
import in.simplifymoney.ledgersync.store.LedgerStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Reads a corpus of raw messages and puts transactions in the ledger.
 *
 * Each message is parsed on its own, but nothing is saved message-by-message:
 * the whole batch is handed to TransactionAssembler first, which merges
 * evidence for the same real-world transaction (a message uploaded twice, or
 * reported on more than one channel) and decides MICRO/TRANSFER, which needs
 * to see other messages in the batch to work out. Only the merged,
 * categorised transactions are saved - and LedgerStore.save() is itself
 * idempotent by natural key, so re-running this against the same or an
 * overlapping corpus leaves the ledger unchanged.
 */
public final class IngestService {

    private final Parsers parsers;
    private final LedgerStore store;

    public IngestService(Parsers parsers, LedgerStore store) {
        this.parsers = parsers;
        this.store = store;
    }

    public Stats ingestFile(Path corpus) throws IOException {
        List<RawMessage> messages = readCorpus(corpus);

        List<ParsedTxn> parsed = new ArrayList<>();
        int skipped = 0;
        for (RawMessage m : messages) {
            Optional<ParsedTxn> p = parsers.parse(m);
            if (p.isEmpty()) {
                skipped++;
            } else {
                parsed.add(p.get());
            }
        }

        TransactionAssembler.Assembled assembled = TransactionAssembler.assemble(parsed);
        for (NormalizedTxn txn : assembled.transactions()) {
            store.save(txn);
        }
        for (BalanceSnapshot s : assembled.balanceSnapshots()) {
            store.saveBalanceSnapshot(s);
        }

        return new Stats(messages.size(), parsed.size(), skipped, assembled.transactions().size());
    }

    public static List<RawMessage> readCorpus(Path corpus) throws IOException {
        List<RawMessage> out = new ArrayList<>();
        try (Stream<String> lines = Files.lines(corpus)) {
            for (String line : (Iterable<String>) lines.filter(s -> !s.isBlank())::iterator) {
                Map<String, Object> o = Json.parseObject(line);
                out.add(new RawMessage(
                        (String) o.get("message_id"),
                        (String) o.get("channel"),
                        (String) o.get("sender"),
                        OffsetDateTime.parse((String) o.get("received_at")),
                        (String) o.get("device_id"),
                        (String) o.get("body")));
            }
        }
        return out;
    }

    /**
     * messagesRead     every line in the corpus
     * messagesMatched  messages a parser recognised as a transaction (before merging)
     * messagesSkipped  messages no parser could read - OTPs, ads, balance
     *                   enquiries, delivery notices, and the like. Not an error.
     * transactionsWritten  real transactions after merging duplicate/multi-channel evidence
     */
    public record Stats(int messagesRead, int messagesMatched, int messagesSkipped,
                         int transactionsWritten) {
        @Override
        public String toString() {
            return "Stats{messagesRead=%d, messagesMatched=%d, messagesSkipped=%d, transactionsWritten=%d}"
                    .formatted(messagesRead, messagesMatched, messagesSkipped, transactionsWritten);
        }
    }
}
