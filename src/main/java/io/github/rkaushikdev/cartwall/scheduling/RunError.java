package io.github.rkaushikdev.cartwall.scheduling;

import io.github.rkaushikdev.cartwall.ValidationError;
import java.util.Map;

/** An order run that could not be started. */
public class RunError extends ValidationError {

    public RunError(String message) {
        super(message);
    }

    public RunError(String message, Map<String, ?> context) {
        super(message, context);
    }

    @Override
    public String code() {
        return "bad_run";
    }
}
