package io.github.rkaushikdev.cartwall.persistence.models;

import static io.github.rkaushikdev.cartwall.persistence.Columns.CODE_LENGTH;
import static io.github.rkaushikdev.cartwall.persistence.Columns.NAME_LENGTH;

import io.github.rkaushikdev.cartwall.domain.Source;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDate;

/**
 * One rule in a station's programming stack, with its place in the stack written down.
 *
 * <p>The grid resolves an hour by taking the last assignment filed that covers it, and "last" has
 * to survive a round trip through a database that has no opinion about row order. The {@code
 * ordinal} column is that order, and reading the assignments without sorting by it will schedule
 * spots into a ballgame.
 */
@Entity
@Table(
        name = "clock_assignment",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uq_clock_assignment_station_effective_ordinal",
                        columnNames = {"station", "effective", "ordinal"}))
public class AssignmentRow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Integer id;

    @Column(name = "station", length = CODE_LENGTH, nullable = false)
    private String station;

    @Column(name = "effective", nullable = false)
    private LocalDate effective;

    @Column(name = "ordinal", nullable = false)
    private int ordinal;

    @Column(name = "label", length = NAME_LENGTH, nullable = false)
    private String label;

    @Column(name = "days", length = 32, nullable = false)
    private String days;

    @Column(name = "first_hour", nullable = false)
    private int firstHour;

    @Column(name = "last_hour", nullable = false)
    private int lastHour;

    @Column(name = "clock_code", length = CODE_LENGTH, nullable = false)
    private String clockCode = "";

    @Column(name = "source", length = 32, nullable = false)
    private Source source = Source.LOCAL;

    /** Declares the foreign key on {@code station}. Written through the code column. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "station",
            insertable = false,
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_clock_assignment_station_station"))
    private StationRow signal;

    protected AssignmentRow() {}

    /**
     * @param ordinal where this sits in the stack; higher wins
     * @param clockCode the clock it runs; empty for a preemption
     */
    public AssignmentRow(
            String station,
            LocalDate effective,
            int ordinal,
            String label,
            String days,
            int firstHour,
            int lastHour,
            String clockCode,
            Source source) {
        this.station = station;
        this.effective = effective;
        this.ordinal = ordinal;
        this.label = label;
        this.days = days;
        this.firstHour = firstHour;
        this.lastHour = lastHour;
        this.clockCode = clockCode;
        this.source = source;
    }

    public Integer getId() {
        return id;
    }

    public String getStation() {
        return station;
    }

    public LocalDate getEffective() {
        return effective;
    }

    public int getOrdinal() {
        return ordinal;
    }

    public String getLabel() {
        return label;
    }

    public String getDays() {
        return days;
    }

    public int getFirstHour() {
        return firstHour;
    }

    public int getLastHour() {
        return lastHour;
    }

    public String getClockCode() {
        return clockCode;
    }

    public Source getSource() {
        return source;
    }

    StationRow getSignal() {
        return signal;
    }

    @Override
    public String toString() {
        return "<AssignmentRow " + station + " " + ordinal + " " + label + ">";
    }
}
