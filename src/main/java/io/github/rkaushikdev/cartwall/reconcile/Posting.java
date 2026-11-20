package io.github.rkaushikdev.cartwall.reconcile;

import static io.github.rkaushikdev.cartwall.HouseLog.Field.field;

import io.github.rkaushikdev.cartwall.Details;
import io.github.rkaushikdev.cartwall.HouseLog;
import io.github.rkaushikdev.cartwall.logs.LogLine;
import io.github.rkaushikdev.cartwall.logs.LogState;
import io.github.rkaushikdev.cartwall.persistence.LogRepository;
import io.github.rkaushikdev.cartwall.persistence.models.LogRow;
import io.github.rkaushikdev.cartwall.persistence.models.SpotRow;
import io.github.rkaushikdev.cartwall.playout.Played;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Writing the as-run back onto the log so billing has one place to read, and what one posting run
 * changed.
 *
 * <p>The reconciliation is a comparison; posting is what makes it durable, and it writes onto the
 * spot rows rather than into a table of its own. That is the opposite of the decision made for
 * bumps: a bump is a debt that outlives the log it came off, while what actually aired is a
 * property of the spot itself, read every time the row is. A second table would make every billing
 * query a join that somebody eventually forgets to write.
 *
 * <p>Posting is idempotent: it sets every field from the reconciliation rather than adding to what
 * is there, so a day that gets a corrected as-run at four in the afternoon is posted again and the
 * second answer wins.
 */
public final class Posting {

    private static final String SPOTS_OF_ORDER = "select s from SpotRow s join s.log l"
            + " where s.orderNumber = :order and l.day >= :first and l.day <= :last";

    private final String station;
    private final LocalDate day;
    private int aired;
    private int missed;
    private int moved;
    private int unmatched;

    public Posting(String station, LocalDate day) {
        this.station = station;
        this.day = day;
    }

    /** Write a reconciliation onto the stored log for that day, which has to have aired. */
    public static Posting post(EntityManager entityManager, Reconciliation result) {
        return post(entityManager, result, false);
    }

    /**
     * Write a reconciliation onto the stored log for that day.
     *
     * @param force post against a day that has not aired yet
     */
    public static Posting post(EntityManager entityManager, Reconciliation result, boolean force) {
        LogRow row = new LogRepository(entityManager)
                .row(result.station(), result.day())
                .orElseThrow(() -> new NotAired(
                        result.station() + " has no log for " + result.day(),
                        Details.of(
                                "station", result.station(), "day", result.day().toString())));
        if (row.getState() != LogState.AIRED && !force) {
            throw new NotAired(
                    result.station() + " " + result.day() + " is "
                            + row.getState().value() + ", not aired",
                    Details.of(
                            "station", result.station(),
                            "day", result.day().toString(),
                            "state", row.getState().value()));
        }

        Map<Place, SpotRow> spots = new HashMap<>();
        for (SpotRow spot : row.getSpots()) {
            spots.put(new Place(spot.getOrderNumber(), spot.getLine(), spot.getHour(), spot.getBreakNumber()), spot);
        }
        Posting report = new Posting(result.station(), result.day());

        for (SpotRow spot : row.getSpots()) {
            spot.setAired(false);
            spot.setAiredAt(null);
            spot.setAiredLength(null);
            spot.setAiredStatus(Played.UNKNOWN);
        }

        for (Match match : result.matched()) {
            SpotRow spot = spots.get(Place.of(match.scheduled()));
            if (spot == null) {
                continue;
            }
            spot.setAired(match.billable());
            spot.setAiredAt(match.aired().at());
            spot.setAiredLength(match.aired().length());
            spot.setAiredStatus(match.aired().status());
            if (match.billable()) {
                report.aired += 1;
            }
            if (match.moved()) {
                report.moved += 1;
            }
        }

        for (Missed miss : result.missed()) {
            SpotRow spot = spots.get(Place.of(miss.scheduled()));
            if (spot != null) {
                spot.setAiredStatus(statusNamed(miss.reason()));
            }
            report.missed += 1;
        }

        report.unmatched = result.extra().size();
        entityManager.flush();
        HouseLog.event(
                "asrun.posted",
                field("station", result.station()),
                field("day", result.day()),
                field("aired", report.aired),
                field("missed", report.missed),
                field("moved", report.moved),
                field("unlogged", report.unmatched));
        if (report.missed > 0) {
            HouseLog.warn(
                    "asrun.missed",
                    field("station", result.station()),
                    field("day", result.day()),
                    field("spots", report.missed));
        }
        return report;
    }

    /** Every spot of one order that actually went out, in air order. */
    public static List<SpotRow> airedSpots(EntityManager entityManager, String order, LocalDate first, LocalDate last) {
        return entityManager
                .createQuery(SPOTS_OF_ORDER + " and s.aired = true order by l.day, s.at", SpotRow.class)
                .setParameter("order", order)
                .setParameter("first", first)
                .setParameter("last", last)
                .getResultList();
    }

    /** Every spot of one order that did not go out, on days that have aired. */
    public static List<SpotRow> missedSpots(
            EntityManager entityManager, String order, LocalDate first, LocalDate last) {
        return entityManager
                .createQuery(
                        SPOTS_OF_ORDER + " and s.aired = false and l.state = :aired order by l.day, s.at",
                        SpotRow.class)
                .setParameter("order", order)
                .setParameter("first", first)
                .setParameter("last", last)
                .setParameter("aired", LogState.AIRED)
                .getResultList();
    }

    private static Played statusNamed(String reason) {
        for (Played status : Played.values()) {
            if (status.value().equals(reason)) {
                return status;
            }
        }
        return Played.UNKNOWN;
    }

    public String station() {
        return station;
    }

    public LocalDate day() {
        return day;
    }

    public int aired() {
        return aired;
    }

    public int missed() {
        return missed;
    }

    public int moved() {
        return moved;
    }

    public int unmatched() {
        return unmatched;
    }

    /** The spots the run wrote an answer onto. */
    public int touched() {
        return aired + missed;
    }

    @Override
    public String toString() {
        return station + " " + day + ": " + aired + " aired, " + missed + " missed, " + moved + " moved, " + unmatched
                + " not on the log";
    }

    /** Where a spot sits on the stored log: whose it is, and which break it is in. */
    private record Place(String order, int line, int hour, int breakNumber) {

        static Place of(LogLine entry) {
            return new Place(entry.order(), entry.line(), entry.hour(), entry.breakNumber());
        }
    }
}
