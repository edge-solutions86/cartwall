package io.github.rkaushikdev.cartwall.api;

import io.github.rkaushikdev.cartwall.Details;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * Every key the process will accept, by hash.
 *
 * <p>Six people use this and two of them are scripts, so authentication is an API key in a header
 * and there is no session, no login page and no password reset. Keys are hashed, never held in the
 * clear, and compared in constant time; that is more care than the threat model needs and costs
 * four lines.
 */
public final class KeyRing {

    /** The header a caller's key travels in. */
    public static final String HEADER = "X-Cartwall-Key";

    /** Where the keys come from: {@code name:role:key} entries, semicolon separated. */
    public static final String KEYS_VAR = "CARTWALL_API_KEYS";

    private final Map<String, Caller> keys;

    private KeyRing(Map<String, Caller> keys) {
        this.keys = Collections.unmodifiableMap(new LinkedHashMap<>(keys));
    }

    /** Read {@code dholm:seller:secret;tronan:traffic:other}. Blank entries and spacing are forgiven. */
    public static KeyRing parse(String text) {
        Map<String, Caller> keys = new LinkedHashMap<>();
        for (String entry : text.split(";", -1)) {
            if (entry.strip().isEmpty()) {
                continue;
            }
            String[] parts = entry.split(":", -1);
            if (parts.length != 3) {
                throw new NotAuthenticated(
                        "'" + entry.strip() + "' is not a name, a role and a key", Details.of("entry", entry));
            }
            String name = parts[0].strip();
            String role = parts[1].strip();
            String key = parts[2].strip();
            if (name.isEmpty() || key.isEmpty()) {
                throw new NotAuthenticated("'" + entry.strip() + "' has no name or no key");
            }
            keys.put(digest(key), new Caller(name, Role.named(role)));
        }
        return new KeyRing(keys);
    }

    /** The ring the environment configures. */
    public static KeyRing fromEnvironment() {
        return fromEnvironment(System.getenv()::get);
    }

    /** The ring {@code lookup} configures, for anything that is not the real environment. */
    public static KeyRing fromEnvironment(UnaryOperator<String> lookup) {
        String text = lookup.apply(KEYS_VAR);
        return parse(text == null ? "" : text);
    }

    /** A key as the ring holds it: SHA-256, in hex. */
    public static String digest(String key) {
        try {
            byte[] hashed = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashed);
        } catch (NoSuchAlgorithmException missing) {
            throw new IllegalStateException("this JVM has no SHA-256", missing);
        }
    }

    /** Who holds {@code key}, refusing a missing key and one nobody issued. */
    public Caller caller(Optional<String> key) {
        if (key.isEmpty() || key.get().isEmpty()) {
            throw new NotAuthenticated("no " + HEADER + " header on the request");
        }
        byte[] offered = digest(key.get()).getBytes(StandardCharsets.US_ASCII);
        for (Map.Entry<String, Caller> known : keys.entrySet()) {
            if (MessageDigest.isEqual(known.getKey().getBytes(StandardCharsets.US_ASCII), offered)) {
                return known.getValue();
            }
        }
        throw new NotAuthenticated("that key is not one of ours");
    }

    /** The keys by hash. Read only, and never a key in the clear. */
    public Map<String, Caller> keys() {
        return keys;
    }

    public boolean isEmpty() {
        return keys.isEmpty();
    }

    public int size() {
        return keys.size();
    }
}
