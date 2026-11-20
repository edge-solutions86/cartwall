package io.github.rkaushikdev.cartwall.scheduling;

import static io.github.rkaushikdev.cartwall.Fixtures.MONDAY;
import static io.github.rkaushikdev.cartwall.domain.SpotLength.SIXTY;
import static io.github.rkaushikdev.cartwall.domain.SpotLength.THIRTY;
import static io.github.rkaushikdev.cartwall.scheduling.Pinning.pin;
import static io.github.rkaushikdev.cartwall.scheduling.Pinning.pinnedCount;
import static io.github.rkaushikdev.cartwall.scheduling.Pinning.pinnedOn;
import static io.github.rkaushikdev.cartwall.scheduling.Pinning.putSpot;
import static io.github.rkaushikdev.cartwall.scheduling.Pinning.unpin;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.github.rkaushikdev.cartwall.Fixtures;
import io.github.rkaushikdev.cartwall.domain.AccountBook;
import io.github.rkaushikdev.cartwall.domain.Advertiser;
import io.github.rkaushikdev.cartwall.domain.ClassOfTime;
import io.github.rkaushikdev.cartwall.domain.House;
import io.github.rkaushikdev.cartwall.domain.SpotLength;
import java.util.List;
import java.util.stream.Collectors;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PinningTest {

    private AccountBook accounts;
    private Lay lay;

    @BeforeEach
    void layOutMonday() {
        accounts = Fixtures.accounts();
        House house = Fixtures.house(Fixtures.cluster(), accounts, Fixtures.cards(), Fixtures.programming());
        lay = new Lay(DayBoard.build(house.programming().get("KWLO"), MONDAY), house.book("KWLO", MONDAY));
    }

    private Ask anAsk(String code, SpotLength length, int line, String cart) {
        Advertiser advertiser = accounts.advertiser(code);
        return new Ask("25-1044", line, advertiser, "AMD", length, ClassOfTime.FIXED, cart, advertiser.agency());
    }

    private Ask anAsk() {
        return anAsk("HYVEE", THIRTY, 1, "C1204");
    }

    private Ask anAsk(String code) {
        return anAsk(code, THIRTY, 1, "C1204");
    }

    private Ask anAsk(int line) {
        return anAsk("HYVEE", THIRTY, line, "C1204");
    }

    private void sit(int hour, int number, String code, int line) {
        lay.board().avail(hour, number).add(new Placement("25-0100", line, code, THIRTY, ClassOfTime.IMMEDIATE));
    }

    @Test
    void aPinPutsTheSpotInTheBreakItNames() {
        Pinned pinned = pin(lay, accounts, anAsk(), 7, 5);
        assertThat(pinned.landed().hour()).isEqualTo(7);
        assertThat(pinned.landed().number()).isEqualTo(5);
        assertThat(pinned.landed().cart()).isEqualTo("C1204");
        assertThat(pinned.wasForced()).isFalse();
        assertThat(pinned.toString()).startsWith("pinned to 2025-08-04 hour 7 break 5");
    }

    @Test
    void aPinnedSpotIsMarkedSoNothingMovesIt() {
        pin(lay, accounts, anAsk(), 7, 5);
        Placement placement = lay.board().avail(7, 5).placements().get(0);
        assertThat(placement.fixed()).isTrue();
        assertThat(pinnedCount(lay)).isEqualTo(1);
        assertThat(pinnedOn(lay)).containsExactly(new PinnedSpot(7, 5, placement));
    }

    @Test
    void anOrdinaryPlacementIsNotPinned() {
        putSpot(lay, lay.board().avail(7, 5), anAsk(), "");
        assertThat(lay.board().avail(7, 5).placements().get(0).fixed()).isFalse();
        assertThat(pinnedCount(lay)).isZero();
    }

    @Test
    void aPinIntoAFullBreakIsRefused() {
        for (int line = 1; line <= 6; line++) {
            sit(7, 5, "SLUMB", line);
        }
        PinRefused caught = catchThrowableOfType(PinRefused.class, () -> pin(lay, accounts, anAsk(), 7, 5));
        assertThat(caught).hasMessageContaining("has no room for a :30");
        assertThat(caught.code()).isEqualTo("pin_refused");
        assertThat(caught.context()).containsEntry("number", 5);
    }

    @Test
    void aPinThatBreaksSeparationIsRefusedAndNamesTheRules() {
        sit(7, 5, "DEALC", 1);
        PinRefused caught = catchThrowableOfType(PinRefused.class, () -> pin(lay, accounts, anAsk("DEALF"), 7, 5));
        assertThat(caught).hasMessageContaining("breaks SEP-CAT-BRK");
        assertThat(caught.context().get("rules"))
                .asInstanceOf(InstanceOfAssertFactories.LIST)
                .contains("SEP-CAT-BRK");
        assertThat(lay.board().avail(7, 5).usedUnits()).isEqualTo(1);
    }

    @Test
    void aWrittenReasonLetsTheTrafficDirectorDoItAnyway() {
        sit(7, 5, "DEALC", 1);
        Pinned pinned = pin(lay, accounts, anAsk("DEALF"), 7, 5, "", "client bought the position, Chevy agreed");
        assertThat(pinned.wasForced()).isTrue();
        assertThat(pinned.override()).startsWith("client bought");
        assertThat(pinned.broke().stream()
                        .map(violation -> violation.rule().code())
                        .collect(Collectors.toSet()))
                .contains("SEP-CAT-BRK");
        assertThat(pinned.toString()).contains("over 2 rule(s)");
        assertThat(lay.board().avail(7, 5).usedUnits()).isEqualTo(2);
    }

    @Test
    void aPinThatBreaksNothingIgnoresAnOverrideThatWasOffered() {
        Pinned pinned = pin(lay, accounts, anAsk(), 7, 5, "", "not needed");
        assertThat(pinned.broke()).isEmpty();
    }

    @Test
    void theBuilderPlacesAroundAPinRatherThanOverIt() {
        pin(lay, accounts, anAsk(1), 7, 5);
        Result result = Placer.placeWeek(List.of(lay), accounts, anAsk("HYVEE", THIRTY, 2, ""), 3);
        assertThat(result.placed()).isEqualTo(3);
        assertThat(lay.board().avail(7, 5).placements().get(0).fixed()).isTrue();
        assertThat(lay.board().countFor("25-1044", 1)).isEqualTo(1);
    }

    @Test
    void aPinnedSpotStillCountsForSeparation() {
        pin(lay, accounts, anAsk(1), 7, 5);
        Result result = Placer.placeWeek(List.of(lay), accounts, anAsk("HYVEE", THIRTY, 2, ""), 4);
        List<Integer> hours =
                result.landed().stream().map(Landed::hour).sorted().toList();
        // Nothing else of this advertiser inside half an hour of 7:50.
        assertThat(!hours.contains(8)
                        || lay.board().find("25-1044", 2).stream()
                                .allMatch(avail -> avail.hour() != 8 || avail.number() > 2))
                .isTrue();
    }

    @Test
    void unpinningGivesTheUnitBack() {
        pin(lay, accounts, anAsk(), 7, 5);
        Placement dropped = unpin(lay, "25-1044", 1, 7, 5);
        assertThat(dropped.fixed()).isTrue();
        assertThat(lay.board().avail(7, 5).freeUnits()).isEqualTo(6);
        assertThat(pinnedCount(lay)).isZero();
    }

    @Test
    void unpinningSomethingThatIsNotThere() {
        assertThatThrownBy(() -> unpin(lay, "25-1044", 1, 7, 5))
                .isInstanceOf(PinError.class)
                .hasMessageContaining("is not in KWLO hour 7 break 5");
        assertThatThrownBy(() -> unpin(lay, "25-1044", 1, 7, 9))
                .isInstanceOf(InventoryError.class)
                .hasMessageContaining("no break 9");
    }

    @Test
    void pinsComeBackInAirOrder() {
        pin(lay, accounts, anAsk(3), 9, 2);
        pin(lay, accounts, anAsk(1), 6, 3);
        pin(lay, accounts, anAsk(2), 7, 4);
        assertThat(pinnedOn(lay).stream().map(spot -> List.of(spot.hour(), spot.number())))
                .containsExactly(List.of(6, 3), List.of(7, 4), List.of(9, 2));
    }

    @Test
    void aSixtyPinnedIntoABreakWithOneUnitLeftIsRefused() {
        for (int line = 1; line <= 5; line++) {
            sit(7, 5, "SLUMB", line);
        }
        assertThatThrownBy(() -> pin(lay, accounts, anAsk("HYVEE", SIXTY, 1, "C1204"), 7, 5))
                .isInstanceOf(PinRefused.class)
                .hasMessageContaining("no room for a 1:00");
        assertThat(pin(lay, accounts, anAsk(), 7, 5).landed().number()).isEqualTo(5);
    }

    @Test
    void pinningPrimesTheNeighbourListOnce() {
        sit(7, 2, "SLUMB", 1);
        pin(lay, accounts, anAsk(), 9, 2);
        assertThat(lay.neighbours()).hasSize(2);
    }

    @Test
    void aPinCountedAgainstAnotherDayIsNotCounted() {
        pin(lay, accounts, anAsk(), 7, 5);
        assertThat(pinnedCount(lay, MONDAY)).isEqualTo(1);
        assertThat(pinnedCount(lay, MONDAY.withDayOfMonth(5))).isZero();
    }
}
