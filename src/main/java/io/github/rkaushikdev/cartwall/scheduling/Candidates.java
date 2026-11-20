package io.github.rkaushikdev.cartwall.scheduling;

import io.github.rkaushikdev.cartwall.AirTime;
import io.github.rkaushikdev.cartwall.domain.AccountBook;
import io.github.rkaushikdev.cartwall.domain.Advertiser;
import io.github.rkaushikdev.cartwall.domain.Candidate;
import io.github.rkaushikdev.cartwall.domain.Daypart;
import io.github.rkaushikdev.cartwall.domain.RuleBook;
import io.github.rkaushikdev.cartwall.domain.SpotLength;
import io.github.rkaushikdev.cartwall.domain.Weekday;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Where one spot could go, and which of those places are actually clean.
 *
 * <p>Placement is a search and this is the part that generates the moves. Both the clean and the
 * dirty openings come back: when a line cannot be placed anywhere, the traffic director wants to
 * know it was blocked by separation and not by a full day, and those are two problems with two
 * fixes.
 */
public final class Candidates {

    private Candidates() {}

    /**
     * Everything already on the board, as the separation rules see it.
     *
     * <p>One account lookup per distinct advertiser, not one per placement. A busy Friday has four
     * hundred spots and about forty advertisers.
     */
    public static List<Candidate> boardCandidates(DayBoard board, AccountBook accounts) {
        Map<String, Advertiser> known = new HashMap<>();
        List<Candidate> found = new ArrayList<>();
        for (Avail avail : board.avails()) {
            for (Timed timed : avail.airTimes()) {
                Placement placement = timed.placement();
                Advertiser advertiser = known.computeIfAbsent(placement.advertiser(), accounts::advertiser);
                found.add(new Candidate(
                        board.day(),
                        timed.at(),
                        avail.number(),
                        advertiser,
                        placement.cart(),
                        advertiser.agency(),
                        placement.fixed()));
            }
        }
        return found;
    }

    /**
     * The air time a spot of this length would get if it went in at the back, or nothing when it
     * would not fit. Spots go in at the back because that is what the automation does.
     */
    public static Optional<AirTime> wouldLandAt(Avail avail, SpotLength length) {
        if (!avail.accepts(length)) {
            return Optional.empty();
        }
        AirTime cursor = avail.startsAt();
        for (Placement placement : avail.placements()) {
            cursor = cursor.plus(placement.length().air());
        }
        return Optional.of(cursor);
    }

    /** Avails in the ask's daypart with room for it, in air order. */
    public static List<Avail> eligibleAvails(DayBoard board, RuleBook book, Ask ask) {
        if (!book.dayparts().has(ask.daypart())) {
            throw new PlacementError(book.call() + " does not sell " + ask.daypart());
        }
        Daypart daypart = book.dayparts().of(ask.daypart());
        Weekday weekday = Weekday.of(board.day());
        return board.avails().stream()
                .filter(avail -> daypart.holds(weekday, avail.startsAt()) && avail.accepts(ask.length()))
                .sorted(DayBoard.AIR_ORDER)
                .toList();
    }

    /** Every avail the ask could use, clean or not, in air order, read against the board. */
    public static List<Opening> openings(DayBoard board, RuleBook book, AccountBook accounts, Ask ask) {
        checkPair(board, book);
        return search(board, book, ask, boardCandidates(board, accounts));
    }

    /**
     * Every avail the ask could use, clean or not, in air order.
     *
     * @param neighbours the separation candidates already on the board, kept level by the caller
     */
    public static List<Opening> openings(
            DayBoard board, RuleBook book, AccountBook accounts, Ask ask, List<Candidate> neighbours) {
        checkPair(board, book);
        return search(board, book, ask, neighbours);
    }

    public static List<Opening> cleanOpenings(DayBoard board, RuleBook book, AccountBook accounts, Ask ask) {
        return openings(board, book, accounts, ask).stream()
                .filter(Opening::isClean)
                .toList();
    }

    public static List<Opening> cleanOpenings(
            DayBoard board, RuleBook book, AccountBook accounts, Ask ask, List<Candidate> neighbours) {
        return openings(board, book, accounts, ask, neighbours).stream()
                .filter(Opening::isClean)
                .toList();
    }

    private static void checkPair(DayBoard board, RuleBook book) {
        if (!board.station().equals(book.call())) {
            throw new PlacementError(book.call() + "'s rules were handed " + board.station() + "'s board");
        }
        if (!board.day().equals(book.on())) {
            throw new PlacementError("a book for " + book.on() + " was handed a board for " + board.day());
        }
    }

    private static List<Opening> search(DayBoard board, RuleBook book, Ask ask, List<Candidate> around) {
        List<Opening> found = new ArrayList<>();
        for (Avail avail : eligibleAvails(board, book, ask)) {
            Optional<AirTime> at = wouldLandAt(avail, ask.length());
            if (at.isEmpty()) {
                continue;
            }
            Candidate candidate = new Candidate(
                    board.day(), at.get(), avail.number(), ask.advertiser(), ask.cart(), ask.agency(), false);
            found.add(new Opening(avail, book.separation().violations(candidate, around)));
        }
        return List.copyOf(found);
    }

    /** How many openings each rule cost us, worst first, for the exception report. */
    public static Map<String, Integer> whyBlocked(List<Opening> found) {
        Map<String, Integer> tally = new HashMap<>();
        for (Opening opening : found) {
            for (String code : new LinkedHashSet<>(opening.reasons())) {
                tally.merge(code, 1, Integer::sum);
            }
        }
        Map<String, Integer> ordered = new LinkedHashMap<>();
        tally.entrySet().stream()
                .sorted(Comparator.comparing((Map.Entry<String, Integer> entry) -> -entry.getValue())
                        .thenComparing(Map.Entry::getKey))
                .forEach(entry -> ordered.put(entry.getKey(), entry.getValue()));
        return ordered;
    }
}
