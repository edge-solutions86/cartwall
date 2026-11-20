package io.github.rkaushikdev.cartwall.domain;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One station's week, in force from a date until the next grid replaces it.
 *
 * <p>The grid is a stack of assignments rather than a table of hours, because that is how the
 * decisions arrive: a general rule laid down once and exceptions laid on top of it. The last one
 * filed that covers an hour wins, so the order of the stack is load bearing, and a grid read out of
 * order will schedule spots into a ballgame.
 */
public final class ClockGrid {

    private final String station;
    private final LocalDate effective;
    private final List<Assignment> assignments = new ArrayList<>();
    private final Map<String, Clock> clocks = new LinkedHashMap<>();

    public ClockGrid(String station, LocalDate effective) {
        this.station = station;
        this.effective = effective;
    }

    public String station() {
        return station;
    }

    public LocalDate effective() {
        return effective;
    }

    /** The assignments, in the order they were filed. */
    public List<Assignment> assignments() {
        return Collections.unmodifiableList(assignments);
    }

    /** The clocks filed for this station, by code, in filing order. */
    public Map<String, Clock> clocks() {
        return Collections.unmodifiableMap(clocks);
    }

    public void fileClock(Clock clock) {
        if (clocks.containsKey(clock.code())) {
            throw new ClockError("clock " + clock.code() + " is already filed for " + station);
        }
        clocks.put(clock.code(), clock);
    }

    public void assign(Assignment assignment) {
        if (!assignment.clock().isEmpty() && !clocks.containsKey(assignment.clock())) {
            throw new ProgrammingError(
                    assignment.label() + " names clock " + assignment.clock() + ", which is not filed");
        }
        assignments.add(assignment);
    }

    /** The last assignment filed that covers this hour. */
    public Slot slot(Weekday day, int hour) {
        if (hour < 0 || hour >= Daypart.HOURS_IN_DAY) {
            throw new ProgrammingError("hour " + hour + " is not in the day");
        }
        for (int index = assignments.size() - 1; index >= 0; index--) {
            Assignment assignment = assignments.get(index);
            if (assignment.covers(day, hour)) {
                return new Slot(day, hour, assignment);
            }
        }
        throw new ProgrammingError(station + " has nothing programmed " + day.shortName() + " at " + hour);
    }

    /** The clock that runs, or a refusal if the hour is preempted. */
    public Clock clockFor(Weekday day, int hour) {
        Slot slot = slot(day, hour);
        if (!slot.sells()) {
            throw new ProgrammingError(
                    station + " is " + slot.assignment().source().value() + " " + day.shortName() + " at " + hour
                            + " for " + slot.assignment().label());
        }
        return clocks.get(slot.clock());
    }

    public boolean sells(Weekday day, int hour) {
        try {
            return slot(day, hour).sells();
        } catch (ProgrammingError nothingProgrammed) {
            return false;
        }
    }

    /** Hours nothing has been programmed into. */
    public List<Cell> gaps() {
        List<Cell> found = new ArrayList<>();
        for (Weekday day : Weekday.values()) {
            for (int hour = 0; hour < Daypart.HOURS_IN_DAY; hour++) {
                int at = hour;
                if (assignments.stream().noneMatch(assignment -> assignment.covers(day, at))) {
                    found.add(new Cell(day, hour));
                }
            }
        }
        return List.copyOf(found);
    }

    public boolean isComplete() {
        return gaps().isEmpty();
    }

    public int sellingHours() {
        int total = 0;
        for (Weekday day : Weekday.values()) {
            for (int hour = 0; hour < Daypart.HOURS_IN_DAY; hour++) {
                if (sells(day, hour)) {
                    total++;
                }
            }
        }
        return total;
    }

    /** Every local unit the grid puts on the air in a week. */
    public int unitsAWeek() {
        int total = 0;
        for (Weekday day : Weekday.values()) {
            for (int hour = 0; hour < Daypart.HOURS_IN_DAY; hour++) {
                if (sells(day, hour)) {
                    total += clockFor(day, hour).unitCount();
                }
            }
        }
        return total;
    }
}
