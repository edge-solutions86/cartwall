package io.github.rkaushikdev.cartwall.playout;

/**
 * One fixed-width column of the automation's log file.
 *
 * @param name what the column holds
 * @param width how many characters it takes
 */
public record Column(String name, int width) {}
