package io.github.rkaushikdev.cartwall.scheduling;

import io.github.rkaushikdev.cartwall.ValidationError;
import java.util.Map;

/** A bump that could not be considered. */
public class BumpError extends ValidationError {

    public BumpError(String message) {
        super(message);
    }

    public BumpError(String message, Map<String, ?> context) {
        super(message, context);
    }

    @Override
    public String code() {
        return "bad_bump";
    }
}
