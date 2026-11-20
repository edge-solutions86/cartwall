package io.github.rkaushikdev.cartwall.reports;

import io.github.rkaushikdev.cartwall.ValidationError;
import java.util.Map;

/** A statement of account that cannot be drawn up as asked. */
public class StatementError extends ValidationError {

    public StatementError(String message) {
        super(message);
    }

    public StatementError(String message, Map<String, ?> context) {
        super(message, context);
    }

    @Override
    public String code() {
        return "bad_statement";
    }
}
