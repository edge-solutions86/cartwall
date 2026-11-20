package io.github.rkaushikdev.cartwall.api;

import io.github.rkaushikdev.cartwall.Details;
import java.util.Locale;

/**
 * What a key lets its holder do, as a ladder rather than a set of flags: a higher role can do
 * everything a lower one can. The office genuinely works this way, and a permission matrix is a
 * thing nobody keeps correct.
 *
 * <ul>
 *   <li>{@link #READER} sees everything and changes nothing: the sales laptops.
 *   <li>{@link #SELLER} writes and confirms orders, and cannot touch a log.
 *   <li>{@link #TRAFFIC} does all of that and builds, signs and releases logs.
 *   <li>{@link #MANAGER} does all of that and runs the billing.
 * </ul>
 */
public enum Role {
    READER,
    SELLER,
    TRAFFIC,
    MANAGER;

    /** The role the office writes, in any case and with any spacing. */
    public static Role named(String text) {
        try {
            return valueOf(text.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException notARole) {
            throw new NotAuthenticated("'" + text + "' is not a role", Details.of("role", text));
        }
    }

    /** How the office writes it. */
    public String label() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Whether this role reaches {@code wanted} on the ladder. */
    public boolean can(Role wanted) {
        return compareTo(wanted) >= 0;
    }
}
