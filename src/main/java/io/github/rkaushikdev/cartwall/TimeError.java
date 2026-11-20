package io.github.rkaushikdev.cartwall;

/** A time or a length that cannot be what it says it is. */
public class TimeError extends ValidationError {

    public TimeError(String message) {
        super(message);
    }

    @Override
    public String code() {
        return "bad_time";
    }
}
