package io.github.rkaushikdev.cartwall.logs;

import io.github.rkaushikdev.cartwall.ConflictError;
import java.util.Map;

/** The log has problems that have to be fixed before anybody signs it. */
public class LogNotReady extends ConflictError {

    public LogNotReady(String message) {
        super(message);
    }

    public LogNotReady(String message, Map<String, ?> context) {
        super(message, context);
    }

    @Override
    public String code() {
        return "log_not_ready";
    }
}
