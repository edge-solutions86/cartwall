package io.github.rkaushikdev.cartwall.domain;

/**
 * What a separation rule keeps apart: the same advertiser, conflicting categories, the identical
 * cart, or one agency's spots when an order demands it.
 */
public enum Subject {
    ADVERTISER("advertiser"),
    CATEGORY("category"),
    CART("cart"),
    AGENCY("agency");

    private final String value;

    Subject(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}
