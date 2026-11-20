package io.github.rkaushikdev.cartwall.domain;

import io.github.rkaushikdev.cartwall.ValidationError;
import java.util.Map;

/** A length or a cut that cannot be filed. */
public class SpotError extends ValidationError {

    public SpotError(String message) {
        super(message);
    }

    public SpotError(String message, Map<String, ?> context) {
        super(message, context);
    }

    @Override
    public String code() {
        return "bad_spot";
    }
}
