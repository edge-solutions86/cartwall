package io.github.rkaushikdev.cartwall.scheduling;

import io.github.rkaushikdev.cartwall.ValidationError;
import java.util.Map;

/** A request for a spot that cannot be considered at all. */
public class PlacementError extends ValidationError {

    public PlacementError(String message) {
        super(message);
    }

    public PlacementError(String message, Map<String, ?> context) {
        super(message, context);
    }

    @Override
    public String code() {
        return "bad_placement";
    }
}
