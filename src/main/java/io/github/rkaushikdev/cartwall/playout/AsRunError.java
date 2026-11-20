package io.github.rkaushikdev.cartwall.playout;

import io.github.rkaushikdev.cartwall.ValidationError;
import java.util.Map;

/** An as-run file that cannot be read. */
public class AsRunError extends ValidationError {

    public AsRunError(String message) {
        super(message);
    }

    public AsRunError(String message, Map<String, ?> context) {
        super(message, context);
    }

    @Override
    public String code() {
        return "bad_asrun";
    }
}
