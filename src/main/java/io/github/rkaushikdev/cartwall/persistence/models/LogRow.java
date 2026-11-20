package io.github.rkaushikdev.cartwall.persistence.models;

import static io.github.rkaushikdev.cartwall.persistence.Columns.CODE_LENGTH;
import static io.github.rkaushikdev.cartwall.persistence.Columns.NAME_LENGTH;

import io.github.rkaushikdev.cartwall.logs.LogState;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * One station's day as it was built and as it went out, with its approval and its revision.
 *
 * <p>What is stored is what was sold and where it went. The legal IDs and the holes are rebuilt
 * from the grid on the way out, because the log is a view over the board plus the clock.
 */
@Entity
@Table(
        name = "air_log",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uq_air_log_station_day",
                        columnNames = {"station", "day"}),
        indexes = @Index(name = "ix_air_log_day", columnList = "day"))
public class LogRow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Integer id;

    @Column(name = "station", length = CODE_LENGTH, nullable = false)
    private String station;

    @Column(name = "day", nullable = false)
    private LocalDate day;

    @Column(name = "state", length = 32, nullable = false)
    private LogState state = LogState.DRAFT;

    @Column(name = "revision", nullable = false)
    private int revision;

    @Column(name = "approved_by", length = NAME_LENGTH, nullable = false)
    private String approvedBy = "";

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    @Column(name = "released_at")
    private LocalDateTime releasedAt;

    @OneToMany(mappedBy = "log", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("hour, breakNumber, position")
    private List<SpotRow> spots = new ArrayList<>();

    /** Declares the foreign key on {@code station}. Written through the code column. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "station",
            insertable = false,
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_air_log_station_station"))
    private StationRow signal;

    protected LogRow() {}

    public LogRow(String station, LocalDate day) {
        this.station = station;
        this.day = day;
    }

    public Integer getId() {
        return id;
    }

    public String getStation() {
        return station;
    }

    public LocalDate getDay() {
        return day;
    }

    public LogState getState() {
        return state;
    }

    public void setState(LogState state) {
        this.state = state;
    }

    public int getRevision() {
        return revision;
    }

    public void setRevision(int revision) {
        this.revision = revision;
    }

    public String getApprovedBy() {
        return approvedBy;
    }

    public void setApprovedBy(String approvedBy) {
        this.approvedBy = approvedBy;
    }

    public LocalDateTime getApprovedAt() {
        return approvedAt;
    }

    public void setApprovedAt(LocalDateTime approvedAt) {
        this.approvedAt = approvedAt;
    }

    public LocalDateTime getReleasedAt() {
        return releasedAt;
    }

    public void setReleasedAt(LocalDateTime releasedAt) {
        this.releasedAt = releasedAt;
    }

    /** The spots, by hour, break and position once read back. */
    public List<SpotRow> getSpots() {
        return spots;
    }

    /** Replace the spots, each one pointed back at this log. */
    public void setSpots(List<SpotRow> spots) {
        this.spots.clear();
        spots.forEach(this::addSpot);
    }

    public void addSpot(SpotRow row) {
        row.setLog(this);
        spots.add(row);
    }

    StationRow getSignal() {
        return signal;
    }

    @Override
    public String toString() {
        return "<LogRow " + station + " " + day + " rev " + revision + ">";
    }
}
