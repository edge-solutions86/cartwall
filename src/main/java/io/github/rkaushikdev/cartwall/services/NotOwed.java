package io.github.rkaushikdev.cartwall.services;

import io.github.rkaushikdev.cartwall.ConflictError;
import java.util.Map;

/** Nothing is owed on that line. */
public class NotOwed extends ConflictError {

    public NotOwed(String message) {
        super(message);
    }

    public NotOwed(String message, Map<String, ?> context) {
        super(message, context);
    }

    @Override
    public String code() {
        return "not_owed";
    }
}
