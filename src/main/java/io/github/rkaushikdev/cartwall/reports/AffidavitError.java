package io.github.rkaushikdev.cartwall.reports;

import io.github.rkaushikdev.cartwall.ValidationError;
import java.util.Map;

/** A statement that cannot be sworn to as it stands. */
public class AffidavitError extends ValidationError {

    public AffidavitError(String message) {
        super(message);
    }

    public AffidavitError(String message, Map<String, ?> context) {
        super(message, context);
    }

    @Override
    public String code() {
        return "bad_affidavit";
    }
}
