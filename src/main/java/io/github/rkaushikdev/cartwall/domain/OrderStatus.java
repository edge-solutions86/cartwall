package io.github.rkaushikdev.cartwall.domain;

/** Where an order stands. Only a confirmed order gets scheduled. */
public enum OrderStatus {
    PENDING("pending"),
    CONFIRMED("confirmed"),
    CANCELLED("cancelled");

    private final String value;

    OrderStatus(String value) {
        this.value = value;
    }

    /** The name stored in the database and sent over the API. */
    public String value() {
        return value;
    }

    public boolean maySchedule() {
        return this == CONFIRMED;
    }

    public static OrderStatus fromValue(String value) {
        for (OrderStatus status : values()) {
            if (status.value.equals(value)) {
                return status;
            }
        }
        throw new OrderError("'" + value + "' is not an order status");
    }
}
