package io.github.rkaushikdev.cartwall.scheduling;

/**
 * A hand placed spot and the break it sits in.
 *
 * @param hour the hour
 * @param number the break number
 * @param placement the spot
 */
public record PinnedSpot(int hour, int number, Placement placement) {}
