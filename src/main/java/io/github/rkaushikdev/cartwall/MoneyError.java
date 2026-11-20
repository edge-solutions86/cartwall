package io.github.rkaushikdev.cartwall;

/** An amount that is not an amount. */
public class MoneyError extends ValidationError {

    public MoneyError(String message) {
        super(message);
    }

    @Override
    public String code() {
        return "bad_amount";
    }
}
