package io.github.rkaushikdev.cartwall.persistence;

import io.github.rkaushikdev.cartwall.Details;
import io.github.rkaushikdev.cartwall.LookupFailure;
import io.github.rkaushikdev.cartwall.domain.ClockGrid;
import io.github.rkaushikdev.cartwall.logs.LogState;
import io.github.rkaushikdev.cartwall.logs.LogStatus;
import io.github.rkaushikdev.cartwall.persistence.models.LogRow;
import io.github.rkaushikdev.cartwall.persistence.models.SpotRow;
import io.github.rkaushikdev.cartwall.scheduling.DayBoard;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Days, as they were built and as they went out.
 *
 * <p>Kept apart from the other repositories because it is a different kind of write. A day is a
 * thousand rows, written once a night for six stations, and the only thing in the system that will
 * ever be big enough to care about.
 */
public final class LogRepository {

    private final EntityManager entityManager;

    public LogRepository(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    public boolean exists(String station, LocalDate day) {
        return row(station, day).isPresent();
    }

    public LogStatus status(String station, LocalDate day) {
        return LogStore.statusFrom(require(station, day));
    }

    /** Write a board over whatever is stored, unless it has gone out. */
    public LogStatus save(DayBoard board) {
        return save(board, false);
    }

    /**
     * Write a board over whatever is stored.
     *
     * @param force write over a log that has already gone out, as a revision does
     */
    public LogStatus save(DayBoard board, boolean force) {
        LogRow row = row(board.station(), board.day()).orElse(null);
        if (row == null) {
            row = new LogRow(board.station(), board.day());
            entityManager.persist(row);
        } else if (row.getState().isOut() && !force) {
            throw new LogAlreadyOut(
                    board.station() + " " + board.day() + " is "
                            + row.getState().value() + " at revision " + row.getRevision()
                            + ", revise it before writing over it",
                    Details.of(
                            "station", board.station(),
                            "day", board.day().toString(),
                            "state", row.getState().value(),
                            "revision", row.getRevision()));
        }
        // Clear and flush before writing the new rows. Doing both in one flush lets the database
        // see the inserts before the deletes, which trips the unique constraint on (log, hour,
        // break, position) with a stale row.
        row.getSpots().clear();
        entityManager.flush();
        row.setSpots(LogStore.spotRows(board));
        entityManager.flush();
        return LogStore.statusFrom(row);
    }

    /** The day as it was stored, laid back out on the grid. */
    public DayBoard board(ClockGrid grid, LocalDate day) {
        LogRow row = require(grid.station(), day);
        return LogStore.boardFrom(grid, day, row.getSpots());
    }

    public LogStatus approve(String station, LocalDate day, String who, LocalDateTime when) {
        LogRow row = require(station, day);
        row.setState(LogState.APPROVED);
        row.setApprovedBy(who);
        row.setApprovedAt(when);
        entityManager.flush();
        return LogStore.statusFrom(row);
    }

    public LogStatus release(String station, LocalDate day, LocalDateTime when) {
        LogRow row = require(station, day);
        row.setState(LogState.RELEASED);
        row.setReleasedAt(when);
        entityManager.flush();
        return LogStore.statusFrom(row);
    }

    /** The day is over. Nothing else moves until somebody revises it. */
    public LogStatus markAired(String station, LocalDate day) {
        LogRow row = require(station, day);
        if (row.getState() != LogState.RELEASED) {
            throw new LogAlreadyOut(
                    station + " " + day + " is " + row.getState().value() + " and did not go to air from here",
                    Details.of("station", station, "state", row.getState().value()));
        }
        row.setState(LogState.AIRED);
        entityManager.flush();
        return LogStore.statusFrom(row);
    }

    /** Open the day back up as a draft, one revision higher. */
    public LogStatus revise(String station, LocalDate day) {
        LogRow row = require(station, day);
        row.setState(LogState.DRAFT);
        row.setRevision(row.getRevision() + 1);
        row.setApprovedBy("");
        row.setApprovedAt(null);
        row.setReleasedAt(null);
        entityManager.flush();
        return LogStore.statusFrom(row);
    }

    /** Every stored day of one station in a span, in date order. */
    public List<LogStatus> between(String station, LocalDate first, LocalDate last) {
        return entityManager
                .createQuery(
                        "select l from LogRow l where l.station = :station and l.day >= :first and l.day <= :last"
                                + " order by l.day",
                        LogRow.class)
                .setParameter("station", station)
                .setParameter("first", first)
                .setParameter("last", last)
                .getResultList()
                .stream()
                .map(LogStore::statusFrom)
                .toList();
    }

    /** Every scheduled spot of one order in a span, in air order. */
    public List<SpotRow> spotsFor(String order, LocalDate first, LocalDate last) {
        return entityManager
                .createQuery(
                        "select s from SpotRow s join s.log l where s.orderNumber = :order"
                                + " and l.day >= :first and l.day <= :last order by l.day, s.at",
                        SpotRow.class)
                .setParameter("order", order)
                .setParameter("first", first)
                .setParameter("last", last)
                .getResultList();
    }

    /** The stored row of one station day, for the services that read past the board. */
    public Optional<LogRow> row(String station, LocalDate day) {
        return entityManager
                .createQuery("select l from LogRow l where l.station = :station and l.day = :day", LogRow.class)
                .setParameter("station", station)
                .setParameter("day", day)
                .getResultStream()
                .findFirst();
    }

    private LogRow require(String station, LocalDate day) {
        return row(station, day)
                .orElseThrow(() -> new LookupFailure(
                        station + " has no log for " + day, Details.of("station", station, "day", day.toString())));
    }
}
