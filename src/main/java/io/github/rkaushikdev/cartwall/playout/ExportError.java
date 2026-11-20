package io.github.rkaushikdev.cartwall.playout;

import io.github.rkaushikdev.cartwall.ValidationError;
import java.util.Map;

/** A log that cannot be written in the automation's format. */
public class ExportError extends ValidationError {

    public ExportError(String message) {
        super(message);
    }

    public ExportError(String message, Map<String, ?> context) {
        super(message, context);
    }

    @Override
    public String code() {
        return "bad_export";
    }
}
