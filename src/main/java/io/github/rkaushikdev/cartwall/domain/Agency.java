package io.github.rkaushikdev.cartwall.domain;

import io.github.rkaushikdev.cartwall.Money;
import java.math.BigDecimal;
import java.util.regex.Pattern;

/**
 * A buying agency, with the commission it keeps.
 *
 * @param code the account code
 * @param name the agency's name
 * @param rate the commission it keeps, as a fraction of gross
 * @param advertiserLiable set when the agency places the order but the advertiser stays liable
 */
public record Agency(String code, String name, BigDecimal rate, boolean advertiserLiable) {

    static final Pattern CODE = Pattern.compile("^[A-Z][A-Z0-9]{1,7}$");

    public Agency {
        rate = Decimals.normalized(rate);
        if (!CODE.matcher(code).matches()) {
            throw new AccountError("'" + code + "' is not an account code");
        }
        if (name == null || name.isEmpty()) {
            throw new AccountError("agency " + code + " has no name");
        }
        if (rate.signum() < 0 || rate.compareTo(BigDecimal.ONE) >= 0) {
            throw new AccountError("agency " + code + " keeps " + rate + ", which is not a commission");
        }
    }

    /** An agency at the standard fifteen percent. */
    public Agency(String code, String name) {
        this(code, name, Money.AGENCY_RATE, false);
    }

    /** An agency at its own rate. */
    public Agency(String code, String name, BigDecimal rate) {
        this(code, name, rate, false);
    }

    @Override
    public String toString() {
        return name;
    }
}
