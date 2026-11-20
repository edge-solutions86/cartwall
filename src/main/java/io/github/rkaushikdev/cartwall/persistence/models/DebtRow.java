package io.github.rkaushikdev.cartwall.persistence.models;

import static io.github.rkaushikdev.cartwall.persistence.Columns.CODE_LENGTH;

import io.github.rkaushikdev.cartwall.Money;
import io.github.rkaushikdev.cartwall.domain.ClassOfTime;
import io.github.rkaushikdev.cartwall.domain.SpotLength;
import io.github.rkaushikdev.cartwall.services.Cause;
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
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * One spot owed to one line, on one day, for one reason.
 *
 * <p>A debt is unique on line, day, cause and sequence. The sequence tells apart debts that are
 * identical in every other column: a line short four spots in a week is owed four, and without an
 * ordinal they collapse into one row. The rate is stored on the debt because what a spot was worth
 * is what it was worth on the day it was missed.
 */
@Entity
@Table(
        name = "makegood_debt",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uq_makegood_debt_line_day_cause_sequence",
                        columnNames = {"order_number", "line", "day", "cause", "sequence"}),
        indexes = {
            @Index(name = "ix_makegood_debt_order_number", columnList = "order_number"),
            @Index(name = "ix_makegood_debt_station", columnList = "station"),
            @Index(name = "ix_makegood_debt_day", columnList = "day")
        })
public class DebtRow {

    /** Long enough for a daypart code. */
    public static final int LABEL_LENGTH = 16;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Integer id;

    @Column(name = "order_number", length = 24, nullable = false)
    private String orderNumber;

    @Column(name = "line", nullable = false)
    private int line;

    @Column(name = "advertiser", length = CODE_LENGTH, nullable = false)
    private String advertiser;

    @Column(name = "station", length = CODE_LENGTH, nullable = false)
    private String station;

    @Column(name = "daypart", length = LABEL_LENGTH, nullable = false)
    private String daypart;

    @Column(name = "grade", length = 32, nullable = false)
    private ClassOfTime grade;

    @Column(name = "length", nullable = false)
    private SpotLength length;

    /** What the spot was worth on the day it was missed, not today. */
    @Column(name = "rate", nullable = false)
    private Money rate;

    @Column(name = "day", nullable = false)
    private LocalDate day;

    @Column(name = "cause", length = 32, nullable = false)
    private Cause cause;

    /** Which of several identical debts this is. Counted, never meaningful. */
    @Column(name = "sequence", nullable = false)
    private int sequence = 1;

    @OneToMany(mappedBy = "debt", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<OfferRow> offers = new ArrayList<>();

    /** Declares the foreign key on {@code order_number}. Written through the number column. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "order_number",
            insertable = false,
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_makegood_debt_order_number_sales_order"))
    private OrderRow order;

    protected DebtRow() {}

    public DebtRow(
            String orderNumber,
            int line,
            String advertiser,
            String station,
            String daypart,
            ClassOfTime grade,
            SpotLength length,
            Money rate,
            LocalDate day,
            Cause cause,
            int sequence) {
        this.orderNumber = orderNumber;
        this.line = line;
        this.advertiser = advertiser;
        this.station = station;
        this.daypart = daypart;
        this.grade = grade;
        this.length = length;
        this.rate = rate;
        this.day = day;
        this.cause = cause;
        this.sequence = sequence;
    }

    public Integer getId() {
        return id;
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

    public String getStation() {
        return station;
    }

    public String getDaypart() {
        return daypart;
    }

    public ClassOfTime getGrade() {
        return grade;
    }

    public SpotLength getLength() {
        return length;
    }

    public Money getRate() {
        return rate;
    }

    public LocalDate getDay() {
        return day;
    }

    public Cause getCause() {
        return cause;
    }

    public int getSequence() {
        return sequence;
    }

    /** Every offer made against this debt. */
    public List<OfferRow> getOffers() {
        return offers;
    }

    /** Enter an offer made against this debt on its list. */
    public void addOffer(OfferRow row) {
        offers.add(row);
    }

    OrderRow getOrder() {
        return order;
    }

    @Override
    public String toString() {
        return "<DebtRow " + orderNumber + "-" + line + " " + day + " " + cause.value() + " #" + sequence + ">";
    }
}
