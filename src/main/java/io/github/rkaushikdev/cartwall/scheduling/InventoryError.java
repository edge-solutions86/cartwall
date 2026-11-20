package io.github.rkaushikdev.cartwall.scheduling;

import io.github.rkaushikdev.cartwall.ValidationError;
import java.util.Map;

/** An avail that cannot be built or read as asked. */
public class InventoryError extends ValidationError {

    public InventoryError(String message) {
        super(message);
    }

    public InventoryError(String message, Map<String, ?> context) {
        super(message, context);
    }

    @Override
    public String code() {
        return "bad_inventory";
    }
}
