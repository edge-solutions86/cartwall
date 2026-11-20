package io.github.rkaushikdev.cartwall.reports;

import io.github.rkaushikdev.cartwall.ValidationError;
import java.util.Map;

/** A political file entry that cannot be kept as described. */
public class FileError extends ValidationError {

    public FileError(String message) {
        super(message);
    }

    public FileError(String message, Map<String, ?> context) {
        super(message, context);
    }

    @Override
    public String code() {
        return "bad_political_file";
    }
}
