package io.github.rkaushikdev.cartwall.persistence;

import io.github.rkaushikdev.cartwall.ConflictError;
import java.util.Map;

/** The stored log has gone to air and will not be written over. */
public class LogAlreadyOut extends ConflictError {

    public LogAlreadyOut(String message, Map<String, ?> context) {
        super(message, context);
    }

    @Override
    public String code() {
        return "log_already_out";
    }
}
