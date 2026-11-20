package io.github.rkaushikdev.cartwall.domain;

/** What kind of programming a signal carries, which sets the clock it runs. */
public enum Service {
    MUSIC("music"),
    NEWS_TALK("news talk"),
    SPORTS("sports"),
    FARM("farm");

    private final String value;

    Service(String value) {
        this.value = value;
    }

    /** The short name stored in the database and printed on the station list. */
    public String value() {
        return value;
    }

    public static Service fromValue(String value) {
        for (Service service : values()) {
            if (service.value.equals(value)) {
                return service;
            }
        }
        throw new StationError("'" + value + "' is not a service");
    }
}
