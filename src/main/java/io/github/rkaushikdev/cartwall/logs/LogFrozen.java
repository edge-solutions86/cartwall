package io.github.rkaushikdev.cartwall.logs;

import io.github.rkaushikdev.cartwall.ConflictError;
import java.util.Map;

/** The log has gone out and cannot be changed in place. */
public class LogFrozen extends ConflictError {

    public LogFrozen(String message) {
        super(message);
    }

    public LogFrozen(String message, Map<String, ?> context) {
        super(message, context);
    }

    @Override
    public String code() {
        return "log_frozen";
    }
}
