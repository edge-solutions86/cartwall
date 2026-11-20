package io.github.rkaushikdev.cartwall.services;

import io.github.rkaushikdev.cartwall.ValidationError;
import java.util.Map;

/** An entry that cannot be written. */
public class AuditError extends ValidationError {

    public AuditError(String message) {
        super(message);
    }

    public AuditError(String message, Map<String, ?> context) {
        super(message, context);
    }

    @Override
    public String code() {
        return "bad_audit";
    }
}
