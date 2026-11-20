package io.github.rkaushikdev.cartwall.domain;

import static io.github.rkaushikdev.cartwall.Fixtures.FIXED;
import static io.github.rkaushikdev.cartwall.Fixtures.FRIDAY;
import static io.github.rkaushikdev.cartwall.Fixtures.MONDAY;
import static io.github.rkaushikdev.cartwall.Fixtures.NOTICE;
import static io.github.rkaushikdev.cartwall.Fixtures.SATURDAY;
import static io.github.rkaushikdev.cartwall.domain.DaypartGrid.standardGrid;
import static io.github.rkaushikdev.cartwall.domain.SpotLength.SIXTY;
import static io.github.rkaushikdev.cartwall.domain.SpotLength.THIRTY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.cartwall.Fixtures;
import io.github.rkaushikdev.cartwall.Money;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RulebookTest {

    private Cluster cluster;
    private AccountBook accounts;
    private RateCardBook cards;
    private Map<String, ClockGrid> programming;
    private House house;

    @BeforeEach
    void fileTheHouse() {
        cluster = Fixtures.cluster();
        accounts = Fixtures.accounts();
        cards = Fixtures.cards();
        programming = Fixtures.programming();
        house = Fixtures.house(cluster, accounts, cards, programming);
    }

    private House kwloOnly(Map<String, DaypartGrid> dayparts, Map<String, ClockGrid> grids) {
        return new House(cluster, accounts, cards, dayparts, grids, Map.of());
    }

    @Test
    void theHouseBuildsABookForAStationOnADate() {
        RuleBook book = house.book("KWLO", MONDAY);
        assertThat(book.call()).isEqualTo("KWLO");
        assertThat(book.on()).isEqualTo(MONDAY);
        assertThat(book.card().code()).isEqualTo("KWLO-25");
        assertThat(book.station().commercialLoad()).isEqualTo(12);
    }

    @Test
    void aBookIsPinnedToItsDateAndNothingInsideTakesAnother() {
        assertThat(house.book("KCNZ", MONDAY).clock(5).code()).isEqualTo("FRM");
        assertThat(house.book("KCNZ", SATURDAY).clock(5).code()).isEqualTo("FRM");
        assertThat(house.book("KCNZ", LocalDate.of(2025, 8, 10)).clock(5).code())
                .isEqualTo("TLK");
    }

    @Test
    void theBookReadsTheGridForItsOwnWeekday() {
        RuleBook friday = house.book("KCRR", FRIDAY);
        RuleBook monday = house.book("KCRR", MONDAY);
        assertThat(friday.sells(20)).isFalse();
        assertThat(monday.sells(20)).isTrue();
        assertThat(friday.sellingHours()).doesNotContain(20);
        assertThat(friday.sellingHours()).hasSize(20);
        assertThat(monday.sellingHours()).hasSize(24);
    }

    @Test
    void askingABookForAClockInAPreemptedHour() {
        RuleBook friday = house.book("KCRR", FRIDAY);
        assertThatThrownBy(() -> friday.clock(20))
                .isInstanceOf(ProgrammingError.class)
                .hasMessageContaining("preempted F at 20");
    }

    @Test
    void theBookSaysWhichDaypartsAnHourBelongsToNarrowestFirst() {
        RuleBook monday = house.book("KWLO", MONDAY);
        assertThat(monday.daypartsFor(7)).containsExactly("AMD", "AAA", "ROS");
        assertThat(monday.daypartsFor(2)).containsExactly("OVN", "ROS");
        assertThat(house.book("KWLO", SATURDAY).daypartsFor(7)).containsExactly("SAT", "AAA", "ROS");
    }

    @Test
    void theBookPricesOffTheCardThatWasInForce() {
        RuleBook book = house.book("KWLO", MONDAY);
        assertThat(book.rate("AMD", FIXED, THIRTY)).isEqualTo(Money.dollars(60));
        assertThat(book.rate("AMD", FIXED, SIXTY)).isEqualTo(Money.parse("105.00"));
        assertThat(book.rate("MID", NOTICE, THIRTY)).isEqualTo(Money.dollars(26));
        assertThatThrownBy(() -> book.rate("EVE", FIXED, THIRTY))
                .isInstanceOf(RateError.class)
                .hasMessageContaining("does not quote EVE at fixed");
    }

    @Test
    void theBookAddsUpTheInventoryTheDayActuallyHas() {
        RuleBook monday = house.book("KWLO", MONDAY);
        assertThat(monday.unitsAvailable()).isEqualTo(24 * 24);
        assertThat(monday.commercialMinutes()).isEqualTo(24 * 12);

        RuleBook friday = house.book("KCRR", FRIDAY);
        assertThat(friday.unitsAvailable()).isEqualTo(20 * 30);
        assertThat(friday.commercialMinutes()).isEqualTo(20 * 15);
    }

    @Test
    void theFarmBlockChangesWhatOneStationHasToSell() {
        // Two farm hours at 20 units, twenty two talk hours at 30.
        assertThat(house.book("KCNZ", MONDAY).unitsAvailable()).isEqualTo(2 * 20 + 22 * 30);
        assertThat(house.book("KCNZ", LocalDate.of(2025, 8, 10)).unitsAvailable())
                .isEqualTo(24 * 30);
    }

    @Test
    void theBookReportsHoursWhoseClockCarriesMoreThanTheLicenceLoad() {
        // KWLO says twelve minutes and runs the twelve minute music clock.
        assertThat(house.book("KWLO", MONDAY).overLoad()).isEmpty();
        // KCRR says eighteen and runs the fifteen minute talk clock.
        assertThat(house.book("KCRR", MONDAY).overLoad()).isEmpty();
    }

    @Test
    void aStationCarryingALoadItCannotHoldIsReportedHourByHour() {
        House thin = kwloOnly(Map.of("KWLO", standardGrid()), Map.of("KWLO", programming.get("KWLO")));
        RuleBook book = thin.book("KWLO", MONDAY);
        Station station = book.station();
        RuleBook tighter = new RuleBook(
                Station.builder("KWLO", station.band(), station.frequency(), station.cityOfLicense(), station.service())
                        .commercialLoad(8)
                        .build(),
                MONDAY,
                book.dayparts(),
                book.programming(),
                book.separation(),
                book.card());
        assertThat(tighter.overLoad()).isEqualTo(IntStream.range(0, 24).boxed().toList());
    }

    @Test
    void aStationWithNoSeparationRulesFiledGetsAnEmptyBook() {
        House bare = kwloOnly(Map.of("KWLO", standardGrid()), Map.of("KWLO", programming.get("KWLO")));
        RuleBook book = bare.book("KWLO", MONDAY);
        assertThat(book.separation()).isInstanceOf(SeparationBook.class);
        assertThat(book.separation().size()).isZero();
    }

    @Test
    void aTranslatorHasNoBookOfItsOwnAndSaysWhereToLook() {
        assertThatThrownBy(() -> house.book("KWLO-LP", MONDAY))
                .isInstanceOf(RuleBookError.class)
                .hasMessageContaining("carries KWLO, ask for that station");
        assertThat(house.loggingCall("KWLO-LP")).isEqualTo("KWLO");
        assertThat(house.loggingCall("KWLO")).isEqualTo("KWLO");
    }

    @Test
    void aStationThatIsNotOursHasNoBook() {
        assertThatThrownBy(() -> house.book("WHO", MONDAY))
                .isInstanceOf(StationError.class)
                .hasMessageContaining("not one of ours");
    }

    @Test
    void aStationMissingATableIsRefusedRatherThanDefaulted() {
        House partial = kwloOnly(Map.of(), programming);
        assertThatThrownBy(() -> partial.book("KWLO", MONDAY))
                .isInstanceOf(RuleBookError.class)
                .hasMessageContaining("KWLO has no daypart grid filed");

        House noGrid = kwloOnly(Map.of("KWLO", standardGrid()), Map.of());
        assertThatThrownBy(() -> noGrid.book("KWLO", MONDAY))
                .isInstanceOf(RuleBookError.class)
                .hasMessageContaining("KWLO has no programming grid filed");
    }

    @Test
    void aBookRefusesTablesThatBelongToAnotherStation() {
        RuleBook book = house.book("KWLO", MONDAY);
        RuleBook other = house.book("KCRR", MONDAY);
        assertThatThrownBy(() -> new RuleBook(
                        book.station(), MONDAY, book.dayparts(), other.programming(), book.separation(), book.card()))
                .isInstanceOf(RuleBookError.class)
                .hasMessageContaining("was handed KCRR's programming");
        assertThatThrownBy(() -> new RuleBook(
                        book.station(), MONDAY, book.dayparts(), book.programming(), book.separation(), other.card()))
                .isInstanceOf(RuleBookError.class)
                .hasMessageContaining("was handed KCRR's rate card");
    }

    @Test
    void theHouseHandsBackOneBookForEverySignalThatGetsALog() {
        List<RuleBook> books = house.books(MONDAY);
        assertThat(books).extracting(RuleBook::call).containsExactly("KWLO", "KOEL-FM", "KCRR", "KXEL", "KCNZ");
        assertThat(books).allSatisfy(book -> assertThat(book.on()).isEqualTo(MONDAY));
    }

    @Test
    void askingForABookBeforeAnyRateCardExisted() {
        assertThatThrownBy(() -> house.book("KWLO", LocalDate.of(2024, 6, 1)))
                .isInstanceOf(RateError.class)
                .hasMessageContaining("no card in force");
    }
}
