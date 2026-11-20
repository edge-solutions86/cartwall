package io.github.rkaushikdev.cartwall.billing;

/**
 * Where an order line was bought, which heads its summary and is the same on every detail row.
 *
 * @param station the station
 * @param daypart the daypart code
 */
public record LineHeading(String station, String daypart) {

    /** The heading of a line nobody named a station for. */
    public static final LineHeading NONE = new LineHeading("", "");
}
