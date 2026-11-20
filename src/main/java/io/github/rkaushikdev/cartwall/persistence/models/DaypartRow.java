package io.github.rkaushikdev.cartwall.persistence.models;

import static io.github.rkaushikdev.cartwall.persistence.Columns.CODE_LENGTH;
import static io.github.rkaushikdev.cartwall.persistence.Columns.NAME_LENGTH;

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

/** One daypart a station sells, with its days stored the way they were typed: {@code M-F}. */
@Entity
@Table(
        name = "daypart",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uq_daypart_station_code",
                        columnNames = {"station", "code"}))
public class DaypartRow {

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

    @Column(name = "days", length = 32, nullable = false)
    private String days;

    @Column(name = "first_hour", nullable = false)
    private int firstHour;

    @Column(name = "last_hour", nullable = false)
    private int lastHour;

    /** Declares the foreign key on {@code station}. Written through the code column. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "station",
            insertable = false,
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_daypart_station_station"))
    private StationRow signal;

    protected DaypartRow() {}

    public DaypartRow(String station, String code, String name, String days, int firstHour, int lastHour) {
        this.station = station;
        this.code = code;
        this.name = name;
        this.days = days;
        this.firstHour = firstHour;
        this.lastHour = lastHour;
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

    public String getDays() {
        return days;
    }

    public int getFirstHour() {
        return firstHour;
    }

    public int getLastHour() {
        return lastHour;
    }

    StationRow getSignal() {
        return signal;
    }

    @Override
    public String toString() {
        return "<DaypartRow " + station + " " + code + ">";
    }
}
