package in.simplifymoney.ledgersync.parse;

import in.simplifymoney.ledgersync.model.RawMessage;
import java.util.Optional;

/**
 * Bank transaction alert emails.
 *
 * Not written yet. The corpus contains them and they are currently all dropped.
 */
public final class EmailParser implements MessageParser {

    @Override
    public boolean supports(RawMessage m) {
        return "email".equals(m.channel());
    }

    @Override
    public Optional<ParsedTxn> parse(RawMessage m) {
        throw new UnsupportedOperationException("email parsing is not implemented");
    }
}
