package io.github.rkaushikdev.cartwall;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Anything this package refuses on purpose, with a stable code for each kind of refusal.
 *
 * <p>One root, three shapes underneath it, and a code on every leaf. The code is part of the
 * interface: it goes in the log line, in the JSON body and in the operator's runbook, and it
 * does not change when the message does.
 *
 * <ul>
 *   <li>{@link ValidationError} means the input could not be what it claims.
 *   <li>{@link LookupFailure} means we were asked for something that is not on file.
 *   <li>{@link ConflictError} means the request was well formed and the state says no.
 * </ul>
 *
 * <p>The context carries the specifics that a message would otherwise bury in prose, so a caller
 * can pull out the cart number without parsing English.
 */
public class CartwallError extends RuntimeException {

    private final transient Map<String, Object> context;

    /** A refusal with nothing beside the sentence. */
    public CartwallError(String message) {
        this(message, Map.of());
    }

    /** A refusal and the specifics behind it, in the order given. */
    public CartwallError(String message, Map<String, ?> context) {
        super(message);
        this.context = Collections.unmodifiableMap(new LinkedHashMap<>(context));
    }

    /** The stable code for this kind of refusal. Every leaf overrides it. */
    public String code() {
        return "error";
    }

    /** The specifics behind the sentence. Read only. */
    public Map<String, Object> context() {
        return context;
    }

    /**
     * The code, the message and a copy of the context, for an HTTP body or a log line.
     *
     * <p>The maps are fresh copies, so a caller that edits the body cannot reach back into the
     * error.
     */
    public Map<String, Object> asMap() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code());
        body.put("message", getMessage());
        body.put("context", new LinkedHashMap<>(context));
        return body;
    }

    @Override
    public String toString() {
        return getMessage();
    }
}
