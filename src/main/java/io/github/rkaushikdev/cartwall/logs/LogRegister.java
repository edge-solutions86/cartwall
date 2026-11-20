package io.github.rkaushikdev.cartwall.logs;

import io.github.rkaushikdev.cartwall.Details;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Which logs have been signed, by whom, and how many times.
 *
 * <p>Changing a released log is normal: a client kills copy on Tuesday for a log that went out
 * Friday. What is not normal is changing it quietly, so a change after release makes a revision:
 * the number goes up, the log goes back to draft, and it has to be approved and released again.
 */
public final class LogRegister {

    private static final Comparator<LogStatus> BY_DAY =
            Comparator.comparing(LogStatus::day).thenComparing(LogStatus::station);

    private final Map<StationLog, LogStatus> entries = new LinkedHashMap<>();

    private record StationLog(String station, LocalDate day) {}

    /** The status of one station day, opening a draft if nothing is on file. */
    public LogStatus status(String station, LocalDate day) {
        return entries.computeIfAbsent(new StationLog(station, day), key -> new LogStatus(station, day));
    }

    public boolean mayEdit(String station, LocalDate day) {
        return status(station, day).state().mayEdit();
    }

    public void checkEditable(String station, LocalDate day) {
        LogStatus status = status(station, day);
        if (!status.state().mayEdit()) {
            throw new LogFrozen(
                    station + " " + day + " is " + status.state().value() + " at revision " + status.revision(),
                    Details.of(
                            "station", station,
                            "day", day.toString(),
                            "state", status.state().value(),
                            "revision", status.revision()));
        }
    }

    /** Sign a draft off, refusing while it has problems. */
    public LogStatus approve(DayLog log, String who, LocalDateTime when) {
        if (who == null || who.isEmpty()) {
            throw new ApprovalError("a log has to be approved by somebody");
        }
        LogStatus status = status(log.station(), log.day());
        if (status.state() != LogState.DRAFT) {
            throw new LogFrozen(
                    log.station() + " " + log.day() + " is already "
                            + status.state().value(),
                    Details.of("station", log.station(), "state", status.state().value()));
        }
        List<Problem> found = Approval.problems(log);
        if (!found.isEmpty()) {
            throw new LogNotReady(
                    log.station() + " " + log.day() + " has " + found.size() + " problem(s) to fix first",
                    Details.of(
                            "station", log.station(),
                            "day", log.day().toString(),
                            "problems", found.stream().map(Problem::toString).toList()));
        }
        status.approve(who, when);
        return status;
    }

    public LogStatus release(String station, LocalDate day, LocalDateTime when) {
        LogStatus status = status(station, day);
        if (status.state() != LogState.APPROVED) {
            throw new LogFrozen(
                    station + " " + day + " is " + status.state().value() + " and cannot be released",
                    Details.of("station", station, "state", status.state().value()));
        }
        status.release(when);
        return status;
    }

    public LogStatus markAired(String station, LocalDate day) {
        LogStatus status = status(station, day);
        if (status.state() != LogState.RELEASED) {
            throw new LogFrozen(
                    station + " " + day + " is " + status.state().value() + " and did not go to air from here",
                    Details.of("station", station, "state", status.state().value()));
        }
        status.markAired();
        return status;
    }

    /** Open a released log back up, one revision higher. */
    public LogStatus revise(String station, LocalDate day) {
        LogStatus status = status(station, day);
        if (!status.state().isOut()) {
            throw new ApprovalError(
                    station + " " + day + " is " + status.state().value() + " and does not need a revision");
        }
        status.reopen();
        return status;
    }

    /** Every log that has gone out, by day then station. */
    public List<LogStatus> released() {
        return entries.values().stream()
                .filter(status -> status.state().isOut())
                .sorted(BY_DAY)
                .toList();
    }

    /** Every log still in draft, by day then station. */
    public List<LogStatus> waiting() {
        return entries.values().stream()
                .filter(status -> status.state() == LogState.DRAFT)
                .sorted(BY_DAY)
                .toList();
    }

    public int size() {
        return entries.size();
    }
}
