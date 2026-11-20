package io.github.rkaushikdev.cartwall.services;

import io.github.rkaushikdev.cartwall.ValidationError;
import java.util.Map;

/** An offer that cannot be made as described. */
public class MakegoodError extends ValidationError {

    public MakegoodError(String message) {
        super(message);
    }

    public MakegoodError(String message, Map<String, ?> context) {
        super(message, context);
    }

    @Override
    public String code() {
        return "bad_makegood";
    }
}
