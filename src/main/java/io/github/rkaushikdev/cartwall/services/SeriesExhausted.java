package io.github.rkaushikdev.cartwall.services;

import io.github.rkaushikdev.cartwall.ConflictError;
import java.util.Map;

/** A year's worth of numbers has run out. */
public class SeriesExhausted extends ConflictError {

    public SeriesExhausted(String message) {
        super(message);
    }

    public SeriesExhausted(String message, Map<String, ?> context) {
        super(message, context);
    }

    @Override
    public String code() {
        return "series_exhausted";
    }
}
