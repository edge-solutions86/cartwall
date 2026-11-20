package io.github.rkaushikdev.cartwall.persistence.models;

import static io.github.rkaushikdev.cartwall.persistence.Columns.CODE_LENGTH;

import io.github.rkaushikdev.cartwall.AirTime;
import io.github.rkaushikdev.cartwall.Duration;
import io.github.rkaushikdev.cartwall.domain.ClassOfTime;
import io.github.rkaushikdev.cartwall.domain.SpotLength;
import io.github.rkaushikdev.cartwall.playout.Played;
import io.github.rkaushikdev.cartwall.scheduling.LineKey;
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
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

/**
 * One spot on a stored log, and what the as-run later said about it.
 *
 * <p>The air time is stored even though the board can recompute it: the board's own arithmetic is
 * what the log said when it was built, and the stored value is what it said when it was released.
 * Reading a released day gives back the released times.
 */
@Entity
@Table(
        name = "air_spot",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uq_air_spot_log_id_hour_break_number_position",
                        columnNames = {"log_id", "hour", "break_number", "position"}))
public class SpotRow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Integer id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "log_id", nullable = false, foreignKey = @ForeignKey(name = "fk_air_spot_log_id_air_log"))
    @OnDelete(action = OnDeleteAction.CASCADE)
    private LogRow log;

    @Column(name = "hour", nullable = false)
    private int hour;

    @Column(name = "break_number", nullable = false)
    private int breakNumber;

    /** Where in the break, counting from zero. */
    @Column(name = "position", nullable = false)
    private int position;

    /** What the log said when it went out. Not recomputed on read. */
    @Column(name = "at", nullable = false)
    private AirTime at;

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

    @Column(name = "cart", length = CODE_LENGTH, nullable = false)
    private String cart = "";

    @Column(name = "fixed", nullable = false)
    private boolean fixed;

    /** Whether the as-run says it went out. Written by the posting run, read by billing. */
    @Column(name = "aired", nullable = false)
    private boolean aired;

    /** When it really went out; null until an as-run has been posted against the day. */
    @Column(name = "aired_at")
    private AirTime airedAt;

    /** How long it really ran; null until an as-run has been posted against the day. */
    @Column(name = "aired_length")
    private Duration airedLength;

    @Column(name = "aired_status", length = 32, nullable = false)
    private Played airedStatus = Played.UNKNOWN;

    /** Declares the foreign key on {@code advertiser}. Written through the code column. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "advertiser",
            insertable = false,
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_air_spot_advertiser_advertiser"))
    private AdvertiserRow account;

    protected SpotRow() {}

    public SpotRow(
            int hour,
            int breakNumber,
            int position,
            AirTime at,
            String orderNumber,
            int line,
            String advertiser,
            SpotLength length,
            ClassOfTime grade,
            String cart,
            boolean fixed) {
        this.hour = hour;
        this.breakNumber = breakNumber;
        this.position = position;
        this.at = at;
        this.orderNumber = orderNumber;
        this.line = line;
        this.advertiser = advertiser;
        this.length = length;
        this.grade = grade;
        this.cart = cart;
        this.fixed = fixed;
    }

    public LineKey key() {
        return new LineKey(orderNumber, line);
    }

    public Integer getId() {
        return id;
    }

    public LogRow getLog() {
        return log;
    }

    void setLog(LogRow log) {
        this.log = log;
    }

    public int getHour() {
        return hour;
    }

    public int getBreakNumber() {
        return breakNumber;
    }

    public int getPosition() {
        return position;
    }

    public AirTime getAt() {
        return at;
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

    public String getCart() {
        return cart;
    }

    public boolean isFixed() {
        return fixed;
    }

    public boolean isAired() {
        return aired;
    }

    public void setAired(boolean aired) {
        this.aired = aired;
    }

    public AirTime getAiredAt() {
        return airedAt;
    }

    public void setAiredAt(AirTime airedAt) {
        this.airedAt = airedAt;
    }

    public Duration getAiredLength() {
        return airedLength;
    }

    public void setAiredLength(Duration airedLength) {
        this.airedLength = airedLength;
    }

    public Played getAiredStatus() {
        return airedStatus;
    }

    public void setAiredStatus(Played airedStatus) {
        this.airedStatus = airedStatus;
    }

    AdvertiserRow getAccount() {
        return account;
    }

    @Override
    public String toString() {
        return "<SpotRow " + orderNumber + "-" + line + " " + at + ">";
    }
}
