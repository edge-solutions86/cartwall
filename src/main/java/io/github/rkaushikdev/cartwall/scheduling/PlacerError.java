package io.github.rkaushikdev.cartwall.scheduling;

import io.github.rkaushikdev.cartwall.ValidationError;
import java.util.Map;

/** A run that could not be started. */
public class PlacerError extends ValidationError {

    public PlacerError(String message) {
        super(message);
    }

    public PlacerError(String message, Map<String, ?> context) {
        super(message, context);
    }

    @Override
    public String code() {
        return "bad_placer";
    }
}
