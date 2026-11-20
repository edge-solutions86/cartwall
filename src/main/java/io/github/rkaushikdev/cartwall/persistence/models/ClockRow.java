package io.github.rkaushikdev.cartwall.persistence.models;

import static io.github.rkaushikdev.cartwall.persistence.Columns.CODE_LENGTH;
import static io.github.rkaushikdev.cartwall.persistence.Columns.NAME_LENGTH;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * One clock filed for a station, effective from a date, with its breaks.
 *
 * <p>A clock is stored per station per effective date rather than shared, because "the music
 * clock" on one station is not the same clock on another and changes on its own schedule.
 */
@Entity
@Table(
        name = "clock",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uq_clock_station_code_effective",
                        columnNames = {"station", "code", "effective"}))
public class ClockRow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Integer id;

    @Column(name = "station", length = CODE_LENGTH, nullable = false)
    private String station;

    @Column(name = "code", length = CODE_LENGTH, nullable = false)
    private String code;

    @Column(name = "name", length = NAME_LENGTH, nullable = false)
    private String name;

    @Column(name = "effective", nullable = false)
    private LocalDate effective;

    @OneToMany(mappedBy = "clock", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("number")
    private List<BreakRow> breaks = new ArrayList<>();

    /** Declares the foreign key on {@code station}. Written through the code column. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "station",
            insertable = false,
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_clock_station_station"))
    private StationRow signal;

    protected ClockRow() {}

    public ClockRow(String station, String code, String name, LocalDate effective) {
        this.station = station;
        this.code = code;
        this.name = name;
        this.effective = effective;
    }

    public Integer getId() {
        return id;
    }

    public String getStation() {
        return station;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public LocalDate getEffective() {
        return effective;
    }

    /** The breaks, in number order once read back. */
    public List<BreakRow> getBreaks() {
        return breaks;
    }

    /** Replace the breaks, each one pointed back at this clock. */
    public void setBreaks(List<BreakRow> breaks) {
        this.breaks.clear();
        breaks.forEach(this::addBreak);
    }

    public void addBreak(BreakRow row) {
        row.setClock(this);
        breaks.add(row);
    }

    StationRow getSignal() {
        return signal;
    }

    @Override
    public String toString() {
        return "<ClockRow " + station + " " + code + " " + effective + ">";
    }
}
