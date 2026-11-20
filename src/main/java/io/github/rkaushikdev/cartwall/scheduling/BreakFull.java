package io.github.rkaushikdev.cartwall.scheduling;

import io.github.rkaushikdev.cartwall.ConflictError;
import java.util.Map;

/** The break will not take another spot of that length. */
public class BreakFull extends ConflictError {

    public BreakFull(String message) {
        super(message);
    }

    public BreakFull(String message, Map<String, ?> context) {
        super(message, context);
    }

    @Override
    public String code() {
        return "break_full";
    }
}
