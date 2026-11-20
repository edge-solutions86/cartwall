package io.github.rkaushikdev.cartwall.reports;

import static io.github.rkaushikdev.cartwall.domain.SpotLength.SIXTY;
import static io.github.rkaushikdev.cartwall.domain.SpotLength.THIRTY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.github.rkaushikdev.cartwall.Money;
import io.github.rkaushikdev.cartwall.domain.SpotLength;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AffidavitTest {

    private static final LocalDate MONDAY = LocalDate.of(2025, 8, 4);
    private static final LocalDate FRIDAY = LocalDate.of(2025, 8, 8);
    private static final LocalDate SWORN = LocalDate.of(2025, 8, 11);

    static Ran ran(LocalDate day, String at, String station, SpotLength length, String rate) {
        return new Ran(day, at, station, length, "C1204", Money.parse(rate));
    }

    static Ran ran(LocalDate day, String at) {
        return ran(day, at, "KWLO", THIRTY, "60.00");
    }

    static Ran ran(LocalDate day) {
        return ran(day, "7:10:04a");
    }

    static Ran ran() {
        return ran(MONDAY);
    }

    static Ran ranOn(String station, SpotLength length, String rate) {
        return ran(MONDAY, "7:10:04a", station, length, rate);
    }

    static Affidavit anAffidavit(List<Ran> spots) {
        return new Affidavit("25-1044", "HYVEE", MONDAY, FRIDAY, spots);
    }

    static Affidavit anAffidavit() {
        return anAffidavit(List.of(ran()));
    }

    @Test
    void aSpotWithNoTimeCannotBeSwornTo() {
        assertThatThrownBy(() -> ran(MONDAY, ""))
                .isInstanceOf(AffidavitError.class)
                .hasMessageContaining("has no time and cannot be sworn to");
    }

    @Test
    void aSpotWithNoStationCannotBeSwornTo() {
        assertThatThrownBy(() -> ranOn("", THIRTY, "60.00"))
                .isInstanceOf(AffidavitError.class)
                .hasMessageContaining("names no station");
    }

    @Test
    void anAffidavitThatRunsBackwardsIsRefused() {
        assertThatThrownBy(() -> new Affidavit("25-1044", "HYVEE", FRIDAY, MONDAY, List.of(ran())))
                .isInstanceOf(AffidavitError.class)
                .hasMessageContaining("runs backwards");
    }

    @Test
    void anAffidavitAddsUpWhatRan() {
        Affidavit statement = anAffidavit(List.of(ran(), ran(FRIDAY)));
        assertThat(statement.count()).isEqualTo(2);
        assertThat(statement.value()).isEqualTo(Money.dollars(120));
        assertThat(statement.stations()).containsExactly("KWLO");
    }

    @Test
    void anAffidavitListsTheStationsOnceEachInOrder() {
        Affidavit statement = anAffidavit(List.of(
                ranOn("KWLO", THIRTY, "60.00"), ranOn("KCRR", THIRTY, "60.00"), ranOn("KWLO", THIRTY, "60.00")));
        assertThat(statement.stations()).containsExactly("KCRR", "KWLO");
    }

    @Test
    void anAffidavitWithNoSpotsCannotBeSwornTo() {
        Affidavit statement = anAffidavit(List.of());
        assertThat(statement.missing()).contains("any spots to swear to");
        assertThat(statement.isReady()).isFalse();
    }

    @Test
    void anAffidavitNeedsAPersonAndADate() {
        Affidavit statement = anAffidavit();
        assertThat(statement.missing()).contains("who is swearing to it", "when it was sworn");
        statement.swear("T. Ronan", SWORN);
        assertThat(statement.isReady()).isTrue();
    }

    @Test
    void swearingNeedsSomebody() {
        assertThatThrownBy(() -> anAffidavit().swear("", SWORN))
                .isInstanceOf(AffidavitError.class)
                .hasMessageContaining("somebody has to swear to it");
    }

    @Test
    void anUnswornAffidavitIsRefusedRatherThanRenderedBlank() {
        assertThatThrownBy(() -> anAffidavit().render())
                .isInstanceOf(AffidavitError.class)
                .hasMessageContaining("missing who is swearing to it");
    }

    @Test
    void aSwornAffidavitCarriesEverythingTheAgencyAskedFor() {
        Affidavit statement = anAffidavit(List.of(ran(), ran(FRIDAY, "3:22:00p")));
        statement.swear("T. Ronan", SWORN, "M. Wills, Black Hawk County");
        assertThat(statement.render())
                .contains("AFFIDAVIT OF PERFORMANCE")
                .contains("ORDER        25-1044")
                .contains("ADVERTISER   HYVEE")
                .contains("PERIOD       08/04/2025 to 08/08/2025")
                .contains("STATIONS     KWLO")
                .contains("08/04/2025    7:10:04a  KWLO     :30  C1204")
                .contains("TOTAL        2 spots, $120.00")
                .contains("SWORN BY     T. Ronan")
                .contains("ON           08/11/2025")
                .contains("NOTARY       M. Wills, Black Hawk County");
    }

    @Test
    void anAffidavitWithoutANotaryLeavesTheLineOut() {
        Affidavit statement = anAffidavit();
        statement.swear("T. Ronan", SWORN);
        assertThat(statement.render()).doesNotContain("NOTARY");
    }

    @Test
    void theSpotsRenderInAirOrderHoweverTheyWereHandedIn() {
        Affidavit statement = anAffidavit(List.of(ran(FRIDAY, "3:22:00p"), ran(MONDAY, "7:10:04a")));
        statement.swear("T. Ronan", SWORN);
        String text = statement.render();
        assertThat(text.indexOf("08/04/2025")).isLessThan(text.indexOf("08/08/2025"));
    }

    @Test
    void aSpotThatRanOutsideThePeriodStopsTheDocument() {
        Affidavit statement = anAffidavit(List.of(ran(), ran(LocalDate.of(2025, 8, 20))));
        statement.swear("T. Ronan", SWORN);
        assertThat(statement.outside()).hasSize(1);
        AffidavitError caught = catchThrowableOfType(AffidavitError.class, statement::render);
        assertThat(caught).hasMessageContaining("ran outside");
        assertThat(caught.context()).containsEntry("first", "2025-08-20");
    }

    @Test
    void buildingForAPeriodKeepsOnlyWhatRanInsideIt() {
        Affidavit statement = Affidavit.forPeriod(
                "25-1044", "HYVEE", MONDAY, FRIDAY, List.of(ran(MONDAY), ran(LocalDate.of(2025, 8, 20)), ran(FRIDAY)));
        assertThat(statement.count()).isEqualTo(2);
        assertThat(statement.outside()).isEmpty();
        assertThat(statement.spots()).extracting(Ran::day).containsExactly(MONDAY, FRIDAY);
    }

    @Test
    void aClientWhoBoughtSeveralStationsGetsThemBrokenOut() {
        Affidavit statement = anAffidavit(
                List.of(ranOn("KWLO", THIRTY, "60.00"), ranOn("KWLO", THIRTY, "60.00"), ranOn("KCRR", SIXTY, "40.00")));
        Map<String, StationTally> tally = statement.byStation();
        assertThat(tally.keySet()).containsExactly("KCRR", "KWLO");
        assertThat(tally.get("KWLO")).isEqualTo(new StationTally(2, Money.dollars(120)));
        assertThat(tally.get("KCRR")).isEqualTo(new StationTally(1, Money.dollars(40)));
    }

    @Test
    void anAffidavitReadsAsASentence() {
        assertThat(anAffidavit()).hasToString("25-1044 HYVEE 08/04-08/08: 1 spots $60.00");
    }
}
