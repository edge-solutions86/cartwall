package io.github.rkaushikdev.cartwall.scheduling;

import io.github.rkaushikdev.cartwall.AirTime;

/**
 * A spot and the time it will hit the air, which falls out of the lengths in front of it.
 *
 * @param placement the spot
 * @param at when it airs
 */
public record Timed(Placement placement, AirTime at) {}
