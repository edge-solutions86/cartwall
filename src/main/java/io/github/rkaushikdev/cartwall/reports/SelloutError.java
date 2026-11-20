package io.github.rkaushikdev.cartwall.reports;

import io.github.rkaushikdev.cartwall.ValidationError;
import java.util.Map;

/** A sellout report that cannot be drawn as asked. */
public class SelloutError extends ValidationError {

    public SelloutError(String message) {
        super(message);
    }

    public SelloutError(String message, Map<String, ?> context) {
        super(message, context);
    }

    @Override
    public String code() {
        return "bad_sellout";
    }
}
