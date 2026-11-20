package io.github.rkaushikdev.cartwall.scheduling;

import static io.github.rkaushikdev.cartwall.Fixtures.MONDAY;
import static io.github.rkaushikdev.cartwall.domain.SpotLength.SIXTY;
import static io.github.rkaushikdev.cartwall.domain.SpotLength.THIRTY;
import static io.github.rkaushikdev.cartwall.scheduling.Candidates.boardCandidates;
import static io.github.rkaushikdev.cartwall.scheduling.Candidates.cleanOpenings;
import static io.github.rkaushikdev.cartwall.scheduling.Candidates.eligibleAvails;
import static io.github.rkaushikdev.cartwall.scheduling.Candidates.openings;
import static io.github.rkaushikdev.cartwall.scheduling.Candidates.whyBlocked;
import static io.github.rkaushikdev.cartwall.scheduling.Candidates.wouldLandAt;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.cartwall.AirTime;
import io.github.rkaushikdev.cartwall.Fixtures;
import io.github.rkaushikdev.cartwall.domain.AccountBook;
import io.github.rkaushikdev.cartwall.domain.Advertiser;
import io.github.rkaushikdev.cartwall.domain.Candidate;
import io.github.rkaushikdev.cartwall.domain.ClassOfTime;
import io.github.rkaushikdev.cartwall.domain.House;
import io.github.rkaushikdev.cartwall.domain.RuleBook;
import io.github.rkaushikdev.cartwall.domain.SpotLength;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CandidatesTest {

    private AccountBook accounts;
    private House house;
    private DayBoard board;
    private RuleBook book;

    @BeforeEach
    void layOutMonday() {
        accounts = Fixtures.accounts();
        house = Fixtures.house(Fixtures.cluster(), accounts, Fixtures.cards(), Fixtures.programming());
        board = DayBoard.build(house.programming().get("KWLO"), MONDAY);
        book = house.book("KWLO", MONDAY);
    }

    private Ask anAsk(String code, String daypart, SpotLength length, String cart, int line) {
        Advertiser advertiser = accounts.advertiser(code);
        return new Ask("25-1044", line, advertiser, daypart, length, ClassOfTime.FIXED, cart, advertiser.agency());
    }

    private Ask anAsk() {
        return anAsk("HYVEE", "AMD", THIRTY, "", 1);
    }

    private Ask anAsk(String code) {
        return anAsk(code, "AMD", THIRTY, "", 1);
    }

    private void put(int hour, int number, String code, SpotLength length, int line, String cart) {
        board.avail(hour, number).add(new Placement("25-0900", line, code, length, ClassOfTime.IMMEDIATE, cart, false));
    }

    private void put(int hour, int number, String code, SpotLength length, int line) {
        put(hour, number, code, length, line, "");
    }

    private void put(int hour, int number, String code) {
        put(hour, number, code, THIRTY, 1);
    }

    private static List<List<Integer>> breaks(List<Avail> avails) {
        return avails.stream()
                .map(avail -> List.of(avail.hour(), avail.number()))
                .toList();
    }

    private static Map<List<Integer>, Opening> byBreak(List<Opening> found) {
        return found.stream()
                .collect(Collectors.toMap(opening -> List.of(opening.hour(), opening.number()), Function.identity()));
    }

    @Test
    void anAskNeedsAnOrderAndALine() {
        assertThatThrownBy(() -> new Ask("", 1, accounts.advertiser("HYVEE"), "AMD", THIRTY, ClassOfTime.FIXED))
                .isInstanceOf(PlacementError.class)
                .hasMessageContaining("names no order");
        assertThatThrownBy(() -> anAsk("HYVEE", "AMD", THIRTY, "", 0))
                .isInstanceOf(PlacementError.class)
                .hasMessageContaining("not a line number");
        assertThat(anAsk().key()).isEqualTo(new LineKey("25-1044", 1));
        assertThat(anAsk()).hasToString("25-1044-1 HYVEE :30 AMD");
    }

    @Test
    void onlyTheBreaksInTheAskedDaypartAreEligible() {
        List<Avail> found = eligibleAvails(board, book, anAsk());
        assertThat(found.stream().map(Avail::hour).collect(Collectors.toSet())).isEqualTo(Set.of(6, 7, 8, 9));
        assertThat(found).hasSize(16);
        assertThat(breaks(found).subList(0, 2)).containsExactly(List.of(6, 2), List.of(6, 3));
    }

    @Test
    void aWiderDaypartOffersMoreOfTheDay() {
        List<Avail> tripleA = eligibleAvails(board, book, anAsk("HYVEE", "AAA", THIRTY, "", 1));
        List<Avail> ros = eligibleAvails(board, book, anAsk("HYVEE", "ROS", THIRTY, "", 1));
        assertThat(tripleA.stream().map(Avail::hour).collect(Collectors.toSet()))
                .isEqualTo(IntStream.range(6, 19).boxed().collect(Collectors.toSet()));
        assertThat(ros).hasSize(board.size());
    }

    @Test
    void aDaypartTheStationDoesNotSellIsRefused() {
        assertThatThrownBy(() -> eligibleAvails(board, book, anAsk("HYVEE", "XXX", THIRTY, "", 1)))
                .isInstanceOf(PlacementError.class)
                .hasMessageContaining("does not sell XXX");
    }

    @Test
    void aFullBreakStopsBeingEligible() {
        IntStream.rangeClosed(1, 6).forEach(line -> put(7, 2, "SLUMB", THIRTY, line));
        List<List<Integer>> found = breaks(eligibleAvails(board, book, anAsk()));
        assertThat(found).doesNotContain(List.of(7, 2)).contains(List.of(7, 3));
    }

    @Test
    void aSixtyIsEligibleInFewerPlacesThanAThirty() {
        put(7, 2, "SLUMB", SIXTY, 1);
        put(7, 2, "VGM", SIXTY, 2);
        put(7, 2, "BLACK", THIRTY, 3);
        assertThat(breaks(eligibleAvails(board, book, anAsk()))).contains(List.of(7, 2));
        assertThat(breaks(eligibleAvails(board, book, anAsk("HYVEE", "AMD", SIXTY, "", 1))))
                .doesNotContain(List.of(7, 2));
    }

    @Test
    void aSpotLandsBehindWhatIsAlreadyInTheBreak() {
        Avail avail = board.avail(7, 2);
        assertThat(wouldLandAt(avail, THIRTY)).contains(AirTime.at(7, 10));
        put(7, 2, "SLUMB", SIXTY, 1);
        assertThat(wouldLandAt(avail, THIRTY)).contains(AirTime.at(7, 11));
        put(7, 2, "VGM", SIXTY, 2);
        assertThat(wouldLandAt(avail, SIXTY)).contains(AirTime.at(7, 12));
    }

    @Test
    void aLengthThatWillNotFitLandsNowhere() {
        put(7, 2, "SLUMB", SIXTY, 1);
        put(7, 2, "VGM", SIXTY, 2);
        put(7, 2, "BLACK", SIXTY, 3);
        assertThat(wouldLandAt(board.avail(7, 2), THIRTY)).isEmpty();
    }

    @Test
    void theBoardTurnsIntoSeparationCandidatesWithTheirAirTimes() {
        put(7, 2, "HYVEE", SIXTY, 1);
        put(7, 2, "DEALF", THIRTY, 2);
        put(9, 4, "CASEY", THIRTY, 3);
        List<Candidate> around = boardCandidates(board, accounts);
        assertThat(around).hasSize(3);
        assertThat(around.subList(0, 2))
                .extracting(Candidate::at)
                .containsExactly(AirTime.at(7, 10), AirTime.at(7, 11));
        assertThat(around.get(1).advertiser().code()).isEqualTo("DEALF");
        assertThat(around.get(1).agency()).contains("MOP");
        assertThat(around.get(2).at()).isEqualTo(AirTime.at(9, 38));
    }

    @Test
    void anEmptyBoardOffersEveryBreakClean() {
        List<Opening> found = openings(board, book, accounts, anAsk());
        assertThat(found).hasSize(16);
        assertThat(found).allMatch(Opening::isClean);
        assertThat(found.get(0).toString()).endsWith("clean");
    }

    @Test
    void aBreakHoldingTheSameAdvertiserIsOfferedButNotClean() {
        put(7, 2, "HYVEE");
        Map<List<Integer>, Opening> found = byBreak(openings(board, book, accounts, anAsk()));
        assertThat(found.get(List.of(7, 2)).isClean()).isFalse();
        assertThat(found.get(List.of(7, 2)).reasons()).contains("SEP-ADV");
        assertThat(found.get(List.of(9, 2)).isClean()).isTrue();
    }

    @Test
    void theHalfHourRuleReachesPastTheBreakItWasBrokenIn() {
        put(7, 5, "HYVEE");
        Set<List<Integer>> blocked = openings(board, book, accounts, anAsk()).stream()
                .filter(opening -> !opening.isClean())
                .map(opening -> List.of(opening.hour(), opening.number()))
                .collect(Collectors.toSet());
        // 7:50 plus thirty minutes reaches into the 8 o'clock hour.
        assertThat(blocked).contains(List.of(8, 2)).doesNotContain(List.of(8, 3));
    }

    @Test
    void aCompetingCarDealerBlocksTheBreakAndTheFifteenMinutesAroundIt() {
        put(7, 3, "DEALC");
        Map<List<Integer>, Opening> found = byBreak(openings(board, book, accounts, anAsk("DEALF")));
        assertThat(found.get(List.of(7, 3)).isClean()).isFalse();
        assertThat(found.get(List.of(7, 3)).reasons()).contains("SEP-CAT-BRK", "SEP-CAT-MIN");
        // Break 4 is at :38, sixteen minutes after :22. Far enough.
        assertThat(found.get(List.of(7, 4)).isClean()).isTrue();
    }

    @Test
    void anUnrelatedAdvertiserNeverBlocksAnything() {
        put(7, 2, "SLUMB");
        put(7, 3, "BLACK");
        assertThat(openings(board, book, accounts, anAsk("DEALF"))).allMatch(Opening::isClean);
    }

    @Test
    void theSameCartBlocksTheWholeHourItIsIn() {
        put(7, 2, "HYVEE", THIRTY, 1, "C1204");
        List<Opening> found = openings(board, book, accounts, anAsk("HYVEE", "AMD", THIRTY, "C1204", 1));
        // Isolate the cart rule. The advertiser rule blocks a wider window and the point here is
        // that the cart rule blocks its hour and stops there.
        assertThat(found.stream()
                        .filter(opening -> opening.reasons().contains("SEP-CART"))
                        .map(Opening::hour)
                        .collect(Collectors.toSet()))
                .isEqualTo(Set.of(7));
    }

    @Test
    void cleanOpeningsAreTheOnesTheBuilderMayUse() {
        put(7, 2, "HYVEE");
        List<Opening> every = openings(board, book, accounts, anAsk());
        List<Opening> clean = cleanOpenings(board, book, accounts, anAsk());
        assertThat(clean.size()).isLessThan(every.size());
        assertThat(clean).allMatch(Opening::isClean);
    }

    @Test
    void theExceptionReportSaysWhichRuleCostTheMostOpenings() {
        put(7, 2, "HYVEE");
        put(7, 3, "HYVEE");
        put(8, 2, "HYVEE");
        Map<String, Integer> tally = whyBlocked(openings(board, book, accounts, anAsk()));
        assertThat(tally).containsKey("SEP-ADV");
        assertThat(tally.get("SEP-ADV")).isGreaterThanOrEqualTo(tally.getOrDefault("SEP-CAT-BRK", 0));
        List<String> worstFirst = new ArrayList<>(tally.keySet());
        worstFirst.sort(Comparator.comparing((String code) -> -tally.get(code)).thenComparing(code -> code));
        assertThat(new ArrayList<>(tally.keySet())).isEqualTo(worstFirst);
    }

    @Test
    void aBoardAndABookThatDoNotBelongTogetherAreRefused() {
        RuleBook otherStation = house.book("KCRR", MONDAY);
        assertThatThrownBy(() -> openings(board, otherStation, accounts, anAsk()))
                .isInstanceOf(PlacementError.class)
                .hasMessageContaining("rules were handed KWLO's board");

        RuleBook otherDay = house.book("KWLO", MONDAY.withDayOfMonth(5));
        assertThatThrownBy(() -> openings(board, otherDay, accounts, anAsk()))
                .isInstanceOf(PlacementError.class)
                .hasMessageContaining("was handed a board for 2025-08-04");
    }

    @Test
    void aShortSpotIsEligibleWhereALongOneIsNot() {
        put(7, 2, "SLUMB", SIXTY, 1);
        put(7, 2, "VGM", SIXTY, 2);
        put(7, 2, "BLACK", THIRTY, 3);
        List<Opening> tens = openings(board, book, accounts, anAsk("HYVEE", "AMD", new SpotLength(10), "", 1));
        assertThat(tens.stream().map(opening -> List.of(opening.hour(), opening.number())))
                .contains(List.of(7, 2));
    }
}
