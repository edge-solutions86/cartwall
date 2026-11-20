package io.github.rkaushikdev.cartwall.persistence;

import io.github.rkaushikdev.cartwall.domain.ClockGrid;
import io.github.rkaushikdev.cartwall.logs.LogStatus;
import io.github.rkaushikdev.cartwall.persistence.models.LogRow;
import io.github.rkaushikdev.cartwall.persistence.models.SpotRow;
import io.github.rkaushikdev.cartwall.scheduling.Avail;
import io.github.rkaushikdev.cartwall.scheduling.DayBoard;
import io.github.rkaushikdev.cartwall.scheduling.Placement;
import io.github.rkaushikdev.cartwall.scheduling.Timed;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Storing a built day and reading it back as a board.
 *
 * <p>The round trip is board to rows to board, not log to rows to log. The log is a view over the
 * board plus the clock, and rebuilding it from the grid is both cheaper and more honest than
 * storing the legal IDs and the holes. What is stored is what was sold and where it went.
 */
public final class LogStore {

    private static final Comparator<Avail> AIR_ORDER =
            Comparator.comparingInt(Avail::hour).thenComparing(Avail::position);

    private static final Comparator<SpotRow> SLOT_ORDER = Comparator.comparingInt(SpotRow::getHour)
            .thenComparingInt(SpotRow::getBreakNumber)
            .thenComparingInt(SpotRow::getPosition);

    private LogStore() {}

    /** Every placement on a board, with its position in the break and its air time. */
    public static List<SpotRow> spotRows(DayBoard board) {
        List<SpotRow> rows = new ArrayList<>();
        for (Avail avail : board.avails().stream().sorted(AIR_ORDER).toList()) {
            List<Timed> timed = avail.airTimes();
            for (int position = 0; position < timed.size(); position++) {
                Placement placement = timed.get(position).placement();
                rows.add(new SpotRow(
                        avail.hour(),
                        avail.number(),
                        position,
                        timed.get(position).at(),
                        placement.order(),
                        placement.line(),
                        placement.advertiser(),
                        placement.length(),
                        placement.grade(),
                        placement.cart(),
                        placement.fixed()));
            }
        }
        return rows;
    }

    /** Lay the day out from the grid, then put the stored spots back in it. */
    public static DayBoard boardFrom(ClockGrid grid, LocalDate day, List<SpotRow> rows) {
        DayBoard board = DayBoard.build(grid, day);
        for (SpotRow row : rows.stream().sorted(SLOT_ORDER).toList()) {
            board.avail(row.getHour(), row.getBreakNumber())
                    .add(new Placement(
                            row.getOrderNumber(),
                            row.getLine(),
                            row.getAdvertiser(),
                            row.getLength(),
                            row.getGrade(),
                            row.getCart(),
                            row.isFixed()));
        }
        return board;
    }

    public static LogStatus statusFrom(LogRow row) {
        return new LogStatus(
                row.getStation(),
                row.getDay(),
                row.getState(),
                row.getRevision(),
                row.getApprovedBy(),
                Optional.ofNullable(row.getApprovedAt()),
                Optional.ofNullable(row.getReleasedAt()));
    }
}
