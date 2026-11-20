package io.github.rkaushikdev.cartwall.domain;

/**
 * What one hour of one weekday resolves to on a grid.
 *
 * @param day the weekday
 * @param hour the hour
 * @param assignment the last assignment filed that covers it
 */
public record Slot(Weekday day, int hour, Assignment assignment) {

    public boolean sells() {
        return assignment.source().sells();
    }

    public String clock() {
        return assignment.clock();
    }
}
