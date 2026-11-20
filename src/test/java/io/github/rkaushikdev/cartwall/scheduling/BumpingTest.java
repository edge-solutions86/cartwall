package io.github.rkaushikdev.cartwall.scheduling;

import static io.github.rkaushikdev.cartwall.Fixtures.MONDAY;
import static io.github.rkaushikdev.cartwall.domain.SpotLength.SIXTY;
import static io.github.rkaushikdev.cartwall.domain.SpotLength.THIRTY;
import static io.github.rkaushikdev.cartwall.scheduling.Bumping.bumpInto;
import static io.github.rkaushikdev.cartwall.scheduling.Bumping.enoughToBump;
import static io.github.rkaushikdev.cartwall.scheduling.Bumping.mayBump;
import static io.github.rkaushikdev.cartwall.scheduling.Bumping.preemptible;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.github.rkaushikdev.cartwall.Fixtures;
import io.github.rkaushikdev.cartwall.domain.AccountBook;
import io.github.rkaushikdev.cartwall.domain.Advertiser;
import io.github.rkaushikdev.cartwall.domain.ClassOfTime;
import io.github.rkaushikdev.cartwall.domain.House;
import io.github.rkaushikdev.cartwall.domain.SpotLength;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BumpingTest {

    private static final ClassOfTime FIXED = ClassOfTime.FIXED;
    private static final ClassOfTime NOTICE = ClassOfTime.NOTICE;
    private static final ClassOfTime IMMEDIATE = ClassOfTime.IMMEDIATE;

    // The board is 2025-08-04. Three weeks earlier is well inside the notice window.
    private static final LocalDate LONG_BEFORE = LocalDate.of(2025, 7, 14);
    private static final LocalDate DAY_BEFORE = LocalDate.of(2025, 8, 3);

    private AccountBook accounts;
    private DayBoard board;
    private Avail avail;

    @BeforeEach
    void layOutMonday() {
        accounts = Fixtures.accounts();
        House house = Fixtures.house(Fixtures.cluster(), accounts, Fixtures.cards(), Fixtures.programming());
        board = DayBoard.build(house.programming().get("KWLO"), MONDAY);
        avail = board.avail(7, 2);
    }

    private Ask anAsk(ClassOfTime grade, SpotLength length, int line) {
        Advertiser advertiser = accounts.advertiser("DEALF");
        return new Ask("25-9000", line, advertiser, "AMD", length, grade, "", advertiser.agency());
    }

    private Ask anAsk() {
        return anAsk(FIXED, THIRTY, 1);
    }

    private static Placement sit(Avail avail, ClassOfTime grade, SpotLength length, int line, boolean fixed) {
        Placement placement = new Placement("25-0100", line, "SLUMB", length, grade, "", fixed);
        avail.add(placement);
        return placement;
    }

    private static Placement sit(Avail avail, ClassOfTime grade, SpotLength length, int line) {
        return sit(avail, grade, length, line, false);
    }

    private static Placement sit(Avail avail, ClassOfTime grade, int line) {
        return sit(avail, grade, THIRTY, line);
    }

    private void fillWith(ClassOfTime grade) {
        for (int line = 1; line <= 6; line++) {
            sit(avail, grade, line);
        }
    }

    @Test
    void aHigherClassMayTakeAUnitOffALowerOne() {
        assertThat(mayBump(IMMEDIATE, FIXED, MONDAY, LONG_BEFORE)).isTrue();
        assertThat(mayBump(IMMEDIATE, NOTICE, MONDAY, LONG_BEFORE)).isTrue();
        assertThat(mayBump(NOTICE, FIXED, MONDAY, LONG_BEFORE)).isTrue();
    }

    @Test
    void anEqualClassNeverBumpsHoweverMuchMoneyIsOnIt() {
        for (ClassOfTime grade : ClassOfTime.values()) {
            assertThat(mayBump(grade, grade, MONDAY, LONG_BEFORE)).isFalse();
        }
    }

    @Test
    void aLowerClassNeverBumpsAHigherOne() {
        assertThat(mayBump(FIXED, NOTICE, MONDAY, LONG_BEFORE)).isFalse();
        assertThat(mayBump(FIXED, IMMEDIATE, MONDAY, LONG_BEFORE)).isFalse();
        assertThat(mayBump(NOTICE, IMMEDIATE, MONDAY, LONG_BEFORE)).isFalse();
    }

    @Test
    void fixedPositionIsNeverBumpedAtAnyNotice() {
        assertThat(mayBump(FIXED, FIXED, MONDAY, LONG_BEFORE)).isFalse();
        assertThat(mayBump(FIXED, FIXED, MONDAY, LocalDate.of(2020, 1, 1))).isFalse();
    }

    @Test
    void theNoticeClassNeedsAFortnightAndCountsItToTheAirDate() {
        // 2025-08-04 less fourteen days is 2025-07-21.
        assertThat(mayBump(NOTICE, FIXED, MONDAY, LocalDate.of(2025, 7, 21))).isTrue();
        assertThat(mayBump(NOTICE, FIXED, MONDAY, LocalDate.of(2025, 7, 22))).isFalse();
        assertThat(mayBump(NOTICE, FIXED, MONDAY, DAY_BEFORE)).isFalse();
    }

    @Test
    void theImmediateClassCanBeBumpedTheMorningOf() {
        assertThat(mayBump(IMMEDIATE, FIXED, MONDAY, MONDAY)).isTrue();
        assertThat(mayBump(IMMEDIATE, NOTICE, MONDAY, MONDAY)).isTrue();
    }

    @Test
    void whatIsPreemptibleComesBackCheapestPromiseFirst() {
        sit(avail, NOTICE, 1);
        sit(avail, IMMEDIATE, 2);
        sit(avail, IMMEDIATE, SIXTY, 3);
        assertThat(preemptible(avail, anAsk(), LONG_BEFORE))
                .extracting(Placement::line)
                .containsExactly(3, 2, 1);
    }

    @Test
    void aPinnedSpotIsNotPreemptibleEvenAtTheCheapestClass() {
        sit(avail, IMMEDIATE, THIRTY, 1, true);
        sit(avail, IMMEDIATE, 2);
        assertThat(preemptible(avail, anAsk(), LONG_BEFORE))
                .extracting(Placement::line)
                .containsExactly(2);
    }

    @Test
    void aBreakWithRoomNeedsNothingBumped() {
        sit(avail, IMMEDIATE, 1);
        assertThat(enoughToBump(avail, anAsk(), LONG_BEFORE)).contains(List.of());
    }

    @Test
    void oneThirtyComesOffToMakeRoomForAThirty() {
        fillWith(IMMEDIATE);
        List<Placement> taken = enoughToBump(avail, anAsk(), LONG_BEFORE).orElseThrow();
        assertThat(taken).hasSize(1);
        assertThat(taken.get(0).length()).isEqualTo(THIRTY);
    }

    @Test
    void oneSixtyComesOffRatherThanTwoThirties() {
        sit(avail, IMMEDIATE, SIXTY, 1);
        sit(avail, IMMEDIATE, SIXTY, 2);
        sit(avail, IMMEDIATE, SIXTY, 3);
        List<Placement> taken =
                enoughToBump(avail, anAsk(FIXED, SIXTY, 1), LONG_BEFORE).orElseThrow();
        assertThat(taken).hasSize(1);
        assertThat(taken.get(0).length()).isEqualTo(SIXTY);
    }

    @Test
    void twoThirtiesComeOffWhenASixtyNeedsTheRoom() {
        fillWith(IMMEDIATE);
        List<Placement> taken =
                enoughToBump(avail, anAsk(FIXED, SIXTY, 1), LONG_BEFORE).orElseThrow();
        assertThat(taken).hasSize(2).allMatch(placement -> placement.length().equals(THIRTY));
    }

    @Test
    void theCheapestPromiseGoesFirstEvenWhenItIsShorter() {
        sit(avail, NOTICE, SIXTY, 1);
        sit(avail, NOTICE, SIXTY, 2);
        sit(avail, IMMEDIATE, THIRTY, 3);
        sit(avail, IMMEDIATE, THIRTY, 4);
        assertThat(enoughToBump(avail, anAsk(), LONG_BEFORE).orElseThrow())
                .extracting(Placement::line)
                .containsExactly(3);
    }

    @Test
    void aBreakOfFixedPositionSpotsCannotBeBumpedInto() {
        fillWith(FIXED);
        assertThat(enoughToBump(avail, anAsk(), LONG_BEFORE)).isEmpty();
    }

    @Test
    void aBreakOfNoticeSpotsInsideTheWindowCannotBeBumpedInto() {
        fillWith(NOTICE);
        assertThat(enoughToBump(avail, anAsk(), DAY_BEFORE)).isEmpty();
        assertThat(enoughToBump(avail, anAsk(), LONG_BEFORE)).isPresent();
    }

    @Test
    void bumpingPutsTheNewSpotInAndTakesTheOldOneOut() {
        fillWith(IMMEDIATE);
        List<Bumped> removed = bumpInto(avail, anAsk(), LONG_BEFORE);
        assertThat(removed).hasSize(1);
        assertThat(avail.holds("25-9000", 1)).isTrue();
        assertThat(avail.holds("25-0100", removed.get(0).placement().line())).isFalse();
        assertThat(avail.size()).isEqualTo(6);
    }

    @Test
    void whatCameOffSaysWhoTookItAndFromWhere() {
        fillWith(IMMEDIATE);
        Bumped bumped = bumpInto(avail, anAsk(), LONG_BEFORE).get(0);
        assertThat(bumped.day()).isEqualTo(MONDAY);
        assertThat(bumped.hour()).isEqualTo(7);
        assertThat(bumped.number()).isEqualTo(2);
        assertThat(bumped.byOrder()).isEqualTo("25-9000");
        assertThat(bumped.toString()).contains("for 25-9000-1");
    }

    @Test
    void aBreakThatWillNotYieldRefusesAndSaysWhy() {
        fillWith(FIXED);
        NothingToBump caught = catchThrowableOfType(NothingToBump.class, () -> bumpInto(avail, anAsk(), LONG_BEFORE));
        assertThat(caught).hasMessageContaining("may be moved for a fixed spot");
        assertThat(caught.code()).isEqualTo("nothing_to_bump");
        assertThat(caught.context()).containsEntry("hour", 7);
    }

    @Test
    void aDayThatHasAlreadyAiredCannotBeBumpedInto() {
        fillWith(IMMEDIATE);
        assertThatThrownBy(() -> bumpInto(avail, anAsk(), LocalDate.of(2025, 8, 5)))
                .isInstanceOf(BumpError.class)
                .hasMessageContaining("already aired");
    }

    @Test
    void theQueueRemembersWhoIsOwedWhat() {
        BumpQueue queue = new BumpQueue();
        fillWith(IMMEDIATE);
        bumpInto(avail, anAsk(FIXED, THIRTY, 1), LONG_BEFORE, "", queue);
        bumpInto(avail, anAsk(FIXED, THIRTY, 2), LONG_BEFORE, "", queue);
        assertThat(queue.size()).isEqualTo(2);
        Map<LineKey, Integer> owed = queue.owed();
        assertThat(owed.values().stream().mapToInt(Integer::intValue).sum()).isEqualTo(2);
        assertThat(owed.keySet()).allMatch(key -> key.order().equals("25-0100"));
        assertThat(queue.forOrder("25-0100")).hasSize(2);
        assertThat(queue.forOrder("25-9000")).isEmpty();
        assertThat(queue.on(MONDAY)).hasSize(2);
        assertThat(queue.on(LocalDate.of(2025, 8, 5))).isEmpty();
    }

    @Test
    void theQueueReportsTheWorstHitLineFirst() {
        BumpQueue queue = new BumpQueue();
        // One line filling three breaks loses three, another filling two loses two.
        Map<Integer, List<List<Integer>>> breaks = new LinkedHashMap<>();
        breaks.put(1, List.of(List.of(7, 2), List.of(7, 3), List.of(7, 4)));
        breaks.put(2, List.of(List.of(7, 5), List.of(8, 2)));
        breaks.put(3, List.of(List.of(8, 3)));
        int incoming = 1;
        for (Map.Entry<Integer, List<List<Integer>>> entry : breaks.entrySet()) {
            for (List<Integer> place : entry.getValue()) {
                Avail target = board.avail(place.get(0), place.get(1));
                for (int spot = 0; spot < 6; spot++) {
                    sit(target, IMMEDIATE, entry.getKey());
                }
                bumpInto(target, anAsk(FIXED, THIRTY, incoming), LONG_BEFORE, "", queue);
                incoming++;
            }
        }
        Map<LineKey, Integer> owed = queue.owed();
        assertThat(owed.values()).containsExactly(3, 2, 1);
        assertThat(owed).containsEntry(new LineKey("25-0100", 1), 3);
        assertThat(queue.forLine("25-0100", 2)).hasSize(2);
        assertThat(queue.size()).isEqualTo(6);
    }

    @Test
    void aSpotThatBumpsCarriesTheCartItWasGiven() {
        fillWith(IMMEDIATE);
        bumpInto(avail, anAsk(), LONG_BEFORE, "C1204");
        Placement landed = avail.placements().stream()
                .filter(placement -> placement.order().equals("25-9000"))
                .findFirst()
                .orElseThrow();
        assertThat(landed.cart()).isEqualTo("C1204");
        assertThat(landed.grade()).isSameAs(FIXED);
    }
}
