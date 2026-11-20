package io.github.rkaushikdev.cartwall.logs;

import io.github.rkaushikdev.cartwall.ValidationError;
import java.util.Map;

/** A log that cannot be built or read as asked. */
public class LogError extends ValidationError {

    public LogError(String message) {
        super(message);
    }

    public LogError(String message, Map<String, ?> context) {
        super(message, context);
    }

    @Override
    public String code() {
        return "bad_log";
    }
}
