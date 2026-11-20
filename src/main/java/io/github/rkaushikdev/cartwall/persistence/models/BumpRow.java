package io.github.rkaushikdev.cartwall.persistence.models;

import static io.github.rkaushikdev.cartwall.persistence.Columns.CODE_LENGTH;

import io.github.rkaushikdev.cartwall.domain.ClassOfTime;
import io.github.rkaushikdev.cartwall.domain.SpotLength;
import io.github.rkaushikdev.cartwall.scheduling.LineKey;
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
import jakarta.persistence.Table;
import java.time.LocalDate;

/** One spot taken off the air, who took it, and whether anything has been done about it. */
@Entity
@Table(
        name = "bump",
        indexes = {
            @Index(name = "ix_bump_day", columnList = "day"),
            @Index(name = "ix_bump_order_number", columnList = "order_number")
        })
public class BumpRow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Integer id;

    @Column(name = "station", length = CODE_LENGTH, nullable = false)
    private String station;

    @Column(name = "day", nullable = false)
    private LocalDate day;

    @Column(name = "hour", nullable = false)
    private int hour;

    @Column(name = "break_number", nullable = false)
    private int breakNumber;

    @Column(name = "order_number", length = 24, nullable = false)
    private String orderNumber;

    @Column(name = "line", nullable = false)
    private int line;

    @Column(name = "advertiser", length = CODE_LENGTH, nullable = false)
    private String advertiser;

    @Column(name = "length", nullable = false)
    private SpotLength length;

    @Column(name = "grade", length = 32, nullable = false)
    private ClassOfTime grade;

    /** Who took the unit. */
    @Column(name = "by_order", length = 24, nullable = false)
    private String byOrder;

    @Column(name = "by_line", nullable = false)
    private int byLine;

    /** Set when a makegood has been offered and taken for this bump. */
    @Column(name = "made_good", nullable = false)
    private boolean madeGood;

    /** Declares the foreign key on {@code station}. Written through the code column. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "station",
            insertable = false,
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_bump_station_station"))
    private StationRow signal;

    /** Declares the foreign key on {@code advertiser}. Written through the code column. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "advertiser",
            insertable = false,
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_bump_advertiser_advertiser"))
    private AdvertiserRow account;

    protected BumpRow() {}

    public BumpRow(
            String station,
            LocalDate day,
            int hour,
            int breakNumber,
            String orderNumber,
            int line,
            String advertiser,
            SpotLength length,
            ClassOfTime grade,
            String byOrder,
            int byLine) {
        this.station = station;
        this.day = day;
        this.hour = hour;
        this.breakNumber = breakNumber;
        this.orderNumber = orderNumber;
        this.line = line;
        this.advertiser = advertiser;
        this.length = length;
        this.grade = grade;
        this.byOrder = byOrder;
        this.byLine = byLine;
    }

    public LineKey key() {
        return new LineKey(orderNumber, line);
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

    public int getHour() {
        return hour;
    }

    public int getBreakNumber() {
        return breakNumber;
    }

    public String getOrderNumber() {
        return orderNumber;
    }

    public int getLine() {
        return line;
    }

    public String getAdvertiser() {
        return advertiser;
    }

    public SpotLength getLength() {
        return length;
    }

    public ClassOfTime getGrade() {
        return grade;
    }

    public String getByOrder() {
        return byOrder;
    }

    public int getByLine() {
        return byLine;
    }

    public boolean isMadeGood() {
        return madeGood;
    }

    public void setMadeGood(boolean madeGood) {
        this.madeGood = madeGood;
    }

    StationRow getSignal() {
        return signal;
    }

    AdvertiserRow getAccount() {
        return account;
    }

    @Override
    public String toString() {
        return "<BumpRow " + orderNumber + "-" + line + " off " + day + ">";
    }
}
