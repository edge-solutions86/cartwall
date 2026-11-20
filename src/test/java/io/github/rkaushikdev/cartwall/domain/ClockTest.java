package io.github.rkaushikdev.cartwall.domain;

import static io.github.rkaushikdev.cartwall.domain.Break.local;
import static io.github.rkaushikdev.cartwall.domain.Clock.musicClock;
import static io.github.rkaushikdev.cartwall.domain.Clock.talkClock;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.cartwall.AirTime;
import io.github.rkaushikdev.cartwall.Duration;
import java.util.List;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;

class ClockTest {

    private static Break legalIdAt(int seconds, int number) {
        return new Break(Duration.ofSeconds(seconds), number, BreakKind.LEGAL_ID, Duration.ofSeconds(10), 1, false);
    }

    @Test
    void aBreakSitsAtAnOffsetNotAWallClockTime() {
        Break stopset = local(2, 22, 180, 6);
        assertThat(stopset.position()).isEqualTo(Duration.ofSeconds(22 * 60));
        assertThat(stopset.ends()).isEqualTo(Duration.ofSeconds(25 * 60));
        assertThat(stopset.startsAt(7)).isEqualTo(AirTime.at(7, 22));
        assertThat(stopset.startsAt(15)).isEqualTo(AirTime.at(15, 22));
        assertThat(stopset).hasToString("break 2 at 22:00");
    }

    @Test
    void aBreakThatCannotBeDrawn() {
        assertThatThrownBy(() -> local(0, 22, 180, 6))
                .isInstanceOf(ClockError.class)
                .hasMessageContaining("not a break number");
        assertThatThrownBy(() -> local(2, 60, 180, 6))
                .isInstanceOf(ClockError.class)
                .hasMessageContaining("past the hour");
        assertThatThrownBy(() -> local(2, 22, 0, 6))
                .isInstanceOf(ClockError.class)
                .hasMessageContaining("has no length");
        assertThatThrownBy(() -> local(2, 58, 180, 6))
                .isInstanceOf(ClockError.class)
                .hasMessageContaining("runs past the top");
        assertThatThrownBy(() -> local(2, 22, 180, 0))
                .isInstanceOf(ClockError.class)
                .hasMessageContaining("holds no spots");
    }

    @Test
    void aBreakTheNetworkOwnsCannotBeSold() {
        assertThatThrownBy(() -> new Break(Duration.ofSeconds(1800), 4, BreakKind.NETWORK, Duration.ofSeconds(300), 1))
                .isInstanceOf(ClockError.class)
                .hasMessageContaining("cannot be paid");
    }

    @Test
    void breaksKnowWhenTheyRunIntoEachOther() {
        Break first = local(1, 10, 180, 6);
        Break touching = local(2, 13, 180, 6);
        Break runningIn = local(3, 12, 180, 6);
        assertThat(first.overlaps(touching)).isFalse();
        assertThat(first.overlaps(runningIn)).isTrue();
        assertThat(runningIn.overlaps(first)).isTrue();
    }

    @Test
    void theMusicClockIsTwelveMinutesInFourStopsets() {
        Clock clock = musicClock();
        assertThat(clock.commercialMinutes()).isEqualTo(12);
        assertThat(clock.sellable()).hasSize(4);
        assertThat(clock.unitCount()).isEqualTo(24);
        assertThat(clock.size()).isEqualTo(5);
        assertThat(clock).hasToString("MUS (12 minutes)");
    }

    @Test
    void theTalkClockCarriesMoreAndGivesTheHalfHourToTheNetwork() {
        Clock clock = talkClock();
        assertThat(clock.commercialMinutes()).isEqualTo(15);
        assertThat(clock.numbered(4).kind()).isSameAs(BreakKind.NETWORK);
        assertThat(clock.sellable()).doesNotContain(clock.numbered(4));
        assertThat(clock.unitCount()).isEqualTo(30);
    }

    @Test
    void theLegalIdIsInTheClockButNotForSale() {
        Clock clock = musicClock();
        Break legal = clock.numbered(1);
        assertThat(legal.kind()).isSameAs(BreakKind.LEGAL_ID);
        assertThat(legal.kind().sells()).isFalse();
        assertThat(clock.sellable()).doesNotContain(legal);
    }

    @Test
    void breaksComeBackInAirOrderHoweverTheyWereHandedIn() {
        Clock clock = Clock.build(
                "ODD", "out of order", List.of(local(3, 40, 60, 2), local(1, 10, 60, 2), local(2, 25, 60, 2)));
        assertThat(StreamSupport.stream(clock.spliterator(), false).map(Break::number))
                .containsExactly(1, 2, 3);
        assertThat(clock.breaks())
                .extracting(stopset -> stopset.position().seconds())
                .containsExactly(600.0, 1500.0, 2400.0);
    }

    @Test
    void aClockThatCannotBeRun() {
        assertThatThrownBy(() -> Clock.build("", "nameless", List.of(local(1, 10, 60, 2))))
                .isInstanceOf(ClockError.class)
                .hasMessageContaining("needs a code");
        assertThatThrownBy(() -> Clock.build("DUP", "twice", List.of(local(1, 10, 60, 2), local(1, 30, 60, 2))))
                .isInstanceOf(ClockError.class)
                .hasMessageContaining("numbers a break twice");
        assertThatThrownBy(() -> Clock.build("RUN", "overlapping", List.of(local(1, 10, 180, 6), local(2, 12, 180, 6))))
                .isInstanceOf(ClockError.class)
                .hasMessageContaining("running into");
    }

    @Test
    void aClockMayOnlyCallForOneLegalIdAndItSitsNearTheTop() {
        Break legal = legalIdAt(0, 1);
        Break late = legalIdAt(20 * 60, 2);
        assertThatThrownBy(() -> Clock.build("TWO", "two ids", List.of(legal, late)))
                .isInstanceOf(ClockError.class)
                .hasMessageContaining("two legal IDs");
        assertThatThrownBy(() -> Clock.build("LATE", "late id", List.of(late)))
                .isInstanceOf(ClockError.class)
                .hasMessageContaining("puts the legal ID");
    }

    @Test
    void aLegalIdJustBeforeTheTopOfTheHourIsAllowed() {
        Break lateButClose = legalIdAt(59 * 60, 1);
        assertThat(Clock.build("PRE", "id before the join", List.of(lateButClose))
                        .numbered(1))
                .isNotNull();
    }

    @Test
    void askingForABreakTheClockDoesNotHave() {
        assertThatThrownBy(() -> musicClock().numbered(9))
                .isInstanceOf(ClockError.class)
                .hasMessageContaining("no break 9");
    }

    @Test
    void secondsAndUnitsAreTwoDifferentLimits() {
        Clock clock = musicClock();
        // Twelve minutes of air, but only twenty four spots will be taken.
        assertThat(clock.commercialLength()).isEqualTo(Duration.ofSeconds(720));
        assertThat(clock.unitCount()).isEqualTo(24);
        assertThat(clock.numbered(2).length()).isEqualTo(Duration.ofSeconds(180));
        assertThat(clock.numbered(2).units()).isEqualTo(6);
    }

    @Test
    void aClockIsCheckedAgainstTheLoadTheStationSaysItCarries() {
        assertThat(musicClock().fitsLoad(12)).isTrue();
        assertThat(musicClock().fitsLoad(18)).isTrue();
        assertThat(musicClock().fitsLoad(10)).isFalse();
        assertThat(talkClock().fitsLoad(14)).isFalse();
        assertThat(talkClock().fitsLoad(15)).isTrue();
    }
}
