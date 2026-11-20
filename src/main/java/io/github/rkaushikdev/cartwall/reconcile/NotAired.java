package io.github.rkaushikdev.cartwall.reconcile;

import io.github.rkaushikdev.cartwall.ConflictError;
import java.util.Map;

/** A day that has not gone out cannot have an as-run posted against it. */
public class NotAired extends ConflictError {

    public NotAired(String message) {
        super(message);
    }

    public NotAired(String message, Map<String, ?> context) {
        super(message, context);
    }

    @Override
    public String code() {
        return "not_aired";
    }
}
