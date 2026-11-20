package io.github.rkaushikdev.cartwall.reconcile;

import io.github.rkaushikdev.cartwall.ValidationError;
import java.util.Map;

/** A reconciliation that cannot be run as asked. */
public class ReconcileError extends ValidationError {

    public ReconcileError(String message) {
        super(message);
    }

    public ReconcileError(String message, Map<String, ?> context) {
        super(message, context);
    }

    @Override
    public String code() {
        return "bad_reconcile";
    }
}
