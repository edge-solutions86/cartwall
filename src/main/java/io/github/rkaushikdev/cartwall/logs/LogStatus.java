package io.github.rkaushikdev.cartwall.logs;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Where one station day's log has got to, who signed it, and at which revision.
 *
 * <p>Approval is a person's name against a time, not a flag, because when a spot does not run the
 * first question is who approved the log it was on.
 */
public final class LogStatus {

    private final String station;
    private final LocalDate day;
    private LogState state;
    private int revision;
    private String approvedBy;
    private LocalDateTime approvedAt;
    private LocalDateTime releasedAt;

    /** A fresh draft at revision zero. */
    public LogStatus(String station, LocalDate day) {
        this(station, day, LogState.DRAFT, 0, "", Optional.empty(), Optional.empty());
    }

    public LogStatus(
            String station,
            LocalDate day,
            LogState state,
            int revision,
            String approvedBy,
            Optional<LocalDateTime> approvedAt,
            Optional<LocalDateTime> releasedAt) {
        this.station = station;
        this.day = day;
        this.state = state;
        this.revision = revision;
        this.approvedBy = approvedBy;
        this.approvedAt = approvedAt.orElse(null);
        this.releasedAt = releasedAt.orElse(null);
    }

    public String station() {
        return station;
    }

    public LocalDate day() {
        return day;
    }

    public LogState state() {
        return state;
    }

    public int revision() {
        return revision;
    }

    public String approvedBy() {
        return approvedBy;
    }

    public Optional<LocalDateTime> approvedAt() {
        return Optional.ofNullable(approvedAt);
    }

    public Optional<LocalDateTime> releasedAt() {
        return Optional.ofNullable(releasedAt);
    }

    void approve(String who, LocalDateTime when) {
        state = LogState.APPROVED;
        approvedBy = who;
        approvedAt = when;
    }

    void release(LocalDateTime when) {
        state = LogState.RELEASED;
        releasedAt = when;
    }

    void markAired() {
        state = LogState.AIRED;
    }

    void reopen() {
        state = LogState.DRAFT;
        revision++;
        approvedBy = "";
        approvedAt = null;
        releasedAt = null;
    }

    @Override
    public String toString() {
        String stamp = revision == 0 ? "" : " rev " + revision;
        return station + " " + day + " " + state.value() + stamp;
    }
}
