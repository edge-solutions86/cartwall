package io.github.rkaushikdev.cartwall;

/** A setting that cannot be used. */
public class ConfigError extends ValidationError {

    public ConfigError(String message) {
        super(message);
    }

    @Override
    public String code() {
        return "bad_config";
    }
}
