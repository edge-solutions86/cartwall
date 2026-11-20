package io.github.rkaushikdev.cartwall.scheduling;

import io.github.rkaushikdev.cartwall.AirTime;
import io.github.rkaushikdev.cartwall.Details;
import io.github.rkaushikdev.cartwall.domain.AccountBook;
import io.github.rkaushikdev.cartwall.domain.Candidate;
import io.github.rkaushikdev.cartwall.domain.Violation;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;

/**
 * Putting a spot exactly where somebody said, and keeping it there.
 *
 * <p>A client who bought the 7:50 break bought that break, and the next run of the log must not
 * helpfully improve it into the 7:22. Pinning puts the spot in one named break rather than
 * searching, and marks it fixed so no later pass moves it. A pin is checked against separation like
 * anything else and refused when it breaks a rule, unless the traffic director overrides with a
 * written reason, which goes on the record.
 *
 * <p>{@link #putSpot} lives here because the placer needs it too, and the board and the neighbour
 * list have to move together. A placement added without its candidate is a separation check that
 * silently stops working.
 */
public final class Pinning {

    private Pinning() {}

    /** Add a movable spot to a break and keep the neighbour list level with it. */
    public static Landed putSpot(Lay lay, Avail avail, Ask ask, String cart) {
        return putSpot(lay, avail, ask, cart, false);
    }

    /** Add a spot to a break and keep the neighbour list level with it. */
    public static Landed putSpot(Lay lay, Avail avail, Ask ask, String cart, boolean fixed) {
        Optional<AirTime> at = Candidates.wouldLandAt(avail, ask.length());
        if (at.isEmpty()) {
            throw new PinRefused(
                    avail.station() + " hour " + avail.hour() + " break " + avail.number() + " on " + avail.day()
                            + " will not take a " + ask.length(),
                    Details.of("station", avail.station(), "hour", avail.hour(), "number", avail.number()));
        }
        String playing = cart.isEmpty() ? ask.cart() : cart;
        avail.add(new Placement(
                ask.order(), ask.line(), ask.advertiser().code(), ask.length(), ask.grade(), playing, fixed));
        lay.remember(
                new Candidate(lay.day(), at.get(), avail.number(), ask.advertiser(), playing, ask.agency(), fixed));
        return new Landed(lay.day(), avail.hour(), avail.number(), playing);
    }

    /** Put {@code ask} in one named break and mark it unmovable, refusing a rule break. */
    public static Pinned pin(Lay lay, AccountBook accounts, Ask ask, int hour, int number) {
        return pin(lay, accounts, ask, hour, number, "", "");
    }

    /**
     * Put {@code ask} in one named break and mark it unmovable.
     *
     * @param cart the cart to play; empty plays the ask's own
     * @param override the written reason for breaking a rule; empty refuses a rule break
     */
    public static Pinned pin(
            Lay lay, AccountBook accounts, Ask ask, int hour, int number, String cart, String override) {
        if (lay.neighbours().isEmpty()) {
            lay.prime(accounts);
        }
        String station = lay.board().station();
        Avail avail = lay.board().avail(hour, number);
        Optional<AirTime> at = Candidates.wouldLandAt(avail, ask.length());
        if (at.isEmpty()) {
            throw new PinRefused(
                    station + " hour " + hour + " break " + number + " on " + lay.day() + " has no room for a "
                            + ask.length(),
                    Details.of("station", station, "hour", hour, "number", number));
        }
        Candidate candidate = new Candidate(
                lay.day(), at.get(), number, ask.advertiser(), cart.isEmpty() ? ask.cart() : cart, ask.agency(), true);
        List<Violation> broke = lay.book().separation().violations(candidate, lay.neighbours());
        if (!broke.isEmpty() && override.isEmpty()) {
            List<String> rules = List.copyOf(new TreeSet<>(
                    broke.stream().map(violation -> violation.rule().code()).toList()));
            throw new PinRefused(
                    station + " hour " + hour + " break " + number + " on " + lay.day() + " breaks "
                            + String.join(", ", rules),
                    Details.of("station", station, "hour", hour, "number", number, "rules", rules));
        }
        Landed landed = putSpot(lay, avail, ask, cart, true);
        return new Pinned(landed, override, broke);
    }

    /** Take a hand placed spot back off, so the builder can have the unit. */
    public static Placement unpin(Lay lay, String order, int line, int hour, int number) {
        Avail avail = lay.board().avail(hour, number);
        if (!avail.holds(order, line)) {
            throw new PinError(
                    order + "-" + line + " is not in " + lay.board().station() + " hour " + hour + " break " + number);
        }
        Placement dropped = avail.drop(order, line);
        lay.forget();
        return dropped;
    }

    /** Every hand placed spot on a day, in air order. */
    public static List<PinnedSpot> pinnedOn(Lay lay) {
        List<PinnedSpot> found = new ArrayList<>();
        lay.board().avails().stream().sorted(DayBoard.AIR_ORDER).forEach(avail -> {
            for (Placement placement : avail.placements()) {
                if (placement.fixed()) {
                    found.add(new PinnedSpot(avail.hour(), avail.number(), placement));
                }
            }
        });
        return List.copyOf(found);
    }

    public static int pinnedCount(Lay lay) {
        return pinnedOn(lay).size();
    }

    /** Hand placed spots on {@code day}, which is none when the lay is for another day. */
    public static int pinnedCount(Lay lay, LocalDate day) {
        if (!lay.day().equals(day)) {
            return 0;
        }
        return pinnedCount(lay);
    }
}
