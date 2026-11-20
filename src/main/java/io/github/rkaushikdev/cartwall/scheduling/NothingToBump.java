package io.github.rkaushikdev.cartwall.scheduling;

import io.github.rkaushikdev.cartwall.ConflictError;
import java.util.Map;

/** The break is full and none of what is in it may be moved. */
public class NothingToBump extends ConflictError {

    public NothingToBump(String message) {
        super(message);
    }

    public NothingToBump(String message, Map<String, ?> context) {
        super(message, context);
    }

    @Override
    public String code() {
        return "nothing_to_bump";
    }
}
