package io.github.rkaushikdev.cartwall.domain;

import static io.github.rkaushikdev.cartwall.domain.SeparationBook.houseRules;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.cartwall.AirTime;
import io.github.rkaushikdev.cartwall.Duration;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class SeparationTest {

    private static final LocalDate MONDAY = LocalDate.of(2025, 8, 4);
    private static final LocalDate TUESDAY = LocalDate.of(2025, 8, 5);

    private static final Advertiser FORD =
            Advertiser.build("DEALF", "Dahl Ford", "AUTO.DOMESTIC.FORD").withAgency("MOP");
    private static final Advertiser CHEV = Advertiser.build("DEALC", "Community Chevrolet", "AUTO.DOMESTIC.CHEV")
            .withAgency("MOP");
    private static final Advertiser GROUP = Advertiser.build("DEALG", "Dahl Auto Group", "AUTO");
    private static final Advertiser COUCH = Advertiser.build("FURN", "Slumberland", "FURNITURE");

    static Candidate spot(Advertiser advertiser, int hour, int minute, int breakNumber, String cart, LocalDate day) {
        return new Candidate(day, AirTime.at(hour, minute), breakNumber, advertiser, cart, advertiser.agency(), false);
    }

    static Candidate spot(Advertiser advertiser, int hour, int minute, int breakNumber, String cart) {
        return spot(advertiser, hour, minute, breakNumber, cart, MONDAY);
    }

    static Candidate spot(Advertiser advertiser, int hour, int minute, int breakNumber) {
        return spot(advertiser, hour, minute, breakNumber, "");
    }

    static Candidate spot(Advertiser advertiser, int hour, int minute) {
        return spot(advertiser, hour, minute, 2);
    }

    @Test
    void aRuleThatCannotBeFiled() {
        assertThatThrownBy(() -> new SeparationRule("", Subject.ADVERTISER, Scope.MINUTES, 30))
                .isInstanceOf(SeparationError.class)
                .hasMessageContaining("needs a code");
        assertThatThrownBy(() -> new SeparationRule("SEP", Subject.ADVERTISER, Scope.MINUTES, 0))
                .isInstanceOf(SeparationError.class)
                .hasMessageContaining("names none");
        assertThatThrownBy(() -> new SeparationRule("SEP", Subject.CATEGORY, Scope.BREAK, 15))
                .isInstanceOf(SeparationError.class)
                .hasMessageContaining("scoped to the break, not minutes");
    }

    @Test
    void aRuleReadsTheWayItIsWrittenInTheBook() {
        assertThat(new SeparationRule("SEP-ADV", Subject.ADVERTISER, Scope.MINUTES, 30))
                .hasToString("SEP-ADV: advertiser 30 minutes apart");
        assertThat(new SeparationRule("SEP-CAT", Subject.CATEGORY, Scope.BREAK))
                .hasToString("SEP-CAT: category not twice in a break");
    }

    @Test
    void theGapBetweenTwoSpotsDoesNotCareWhichCameFirst() {
        Candidate early = spot(FORD, 7, 10);
        Candidate late = spot(FORD, 7, 40);
        assertThat(early.gapTo(late)).isEqualTo(Duration.ofSeconds(1800));
        assertThat(late.gapTo(early)).isEqualTo(Duration.ofSeconds(1800));
    }

    @Test
    void twoSpotsOnDifferentDaysHaveNoGapToMeasure() {
        assertThatThrownBy(() -> spot(FORD, 7, 10).gapTo(spot(FORD, 7, 40, 2, "", TUESDAY)))
                .isInstanceOf(SeparationError.class)
                .hasMessageContaining("different days");
    }

    @Test
    void theAdvertiserRuleCatchesTheSameClientInsideHalfAnHour() {
        SeparationRule rule = new SeparationRule("SEP-ADV", Subject.ADVERTISER, Scope.MINUTES, 30);
        assertThat(rule.tooClose(spot(FORD, 7, 10), spot(FORD, 7, 39))).isTrue();
        assertThat(rule.tooClose(spot(FORD, 7, 10), spot(FORD, 7, 40))).isFalse();
        assertThat(rule.tooClose(spot(FORD, 7, 10), spot(CHEV, 7, 15))).isFalse();
    }

    @Test
    void aRuleStopsAtTheEndOfTheDay() {
        SeparationRule rule = new SeparationRule("SEP-ADV", Subject.ADVERTISER, Scope.MINUTES, 30);
        assertThat(rule.tooClose(spot(FORD, 23, 55), spot(FORD, 0, 5, 2, "", TUESDAY)))
                .isFalse();
    }

    @Test
    void theCategoryRuleAsksTheConflictCodesAndNotTheAdvertiserCode() {
        SeparationRule rule = new SeparationRule("SEP-CAT", Subject.CATEGORY, Scope.MINUTES, 15);
        // Two different dealers under one group code conflict through AUTO.
        assertThat(rule.tooClose(spot(FORD, 7, 10), spot(GROUP, 7, 20))).isTrue();
        // Two dealers with no shared code do not, however close they sit.
        assertThat(rule.tooClose(spot(FORD, 7, 10), spot(CHEV, 7, 11))).isFalse();
        assertThat(rule.tooClose(spot(FORD, 7, 10), spot(COUCH, 7, 11))).isFalse();
    }

    @Test
    void theBreakRuleAndTheMinuteRuleCatchDifferentThings() {
        SeparationRule inBreak = new SeparationRule("SEP-BRK", Subject.CATEGORY, Scope.BREAK);
        SeparationRule inMinutes = new SeparationRule("SEP-MIN", Subject.CATEGORY, Scope.MINUTES, 4);

        // Same break, five minutes apart. The break rule catches it, minutes do not.
        Candidate one = spot(FORD, 7, 20, 2);
        Candidate other = spot(GROUP, 7, 25, 2);
        assertThat(inBreak.tooClose(one, other)).isTrue();
        assertThat(inMinutes.tooClose(one, other)).isFalse();

        // Different breaks, two minutes apart. Minutes catch it, the break rule does not.
        one = spot(FORD, 7, 22, 2);
        other = spot(GROUP, 7, 24, 3);
        assertThat(inBreak.tooClose(one, other)).isFalse();
        assertThat(inMinutes.tooClose(one, other)).isTrue();
    }

    @Test
    void theBreakRuleDoesNotConfuseBreakTwoOfOneHourWithBreakTwoOfAnother() {
        SeparationRule rule = new SeparationRule("SEP-BRK", Subject.CATEGORY, Scope.BREAK);
        assertThat(rule.tooClose(spot(FORD, 7, 20, 2), spot(GROUP, 8, 20, 2))).isFalse();
    }

    @Test
    void theHourRuleCatchesTheSameCartWhereverItSitsInTheHour() {
        SeparationRule rule = new SeparationRule("SEP-CART", Subject.CART, Scope.HOUR);
        assertThat(rule.tooClose(spot(FORD, 7, 5, 2, "C1204"), spot(FORD, 7, 55, 5, "C1204")))
                .isTrue();
        assertThat(rule.tooClose(spot(FORD, 7, 5, 2, "C1204"), spot(FORD, 8, 5, 2, "C1204")))
                .isFalse();
        assertThat(rule.tooClose(spot(FORD, 7, 5, 2, "C1204"), spot(FORD, 7, 55, 2, "C1205")))
                .isFalse();
    }

    @Test
    void aSpotWithNoCartYetNeverTripsTheCartRule() {
        SeparationRule rule = new SeparationRule("SEP-CART", Subject.CART, Scope.HOUR);
        assertThat(rule.tooClose(spot(FORD, 7, 5), spot(FORD, 7, 55))).isFalse();
    }

    @Test
    void theAgencyRuleOnlyFiresWhenBothSpotsHaveTheSameAgency() {
        SeparationRule rule = new SeparationRule("SEP-AGY", Subject.AGENCY, Scope.BREAK);
        assertThat(rule.tooClose(spot(FORD, 7, 20), spot(CHEV, 7, 25))).isTrue();
        assertThat(rule.tooClose(spot(FORD, 7, 20), spot(GROUP, 7, 25))).isFalse();
    }

    @Test
    void theBookRefusesTheSameRuleCodeTwice() {
        SeparationBook book = new SeparationBook();
        book.file(new SeparationRule("SEP-ADV", Subject.ADVERTISER, Scope.MINUTES, 30));
        assertThatThrownBy(() -> book.file(new SeparationRule("SEP-ADV", Subject.CART, Scope.HOUR)))
                .isInstanceOf(SeparationError.class)
                .hasMessageContaining("already filed");
        assertThatThrownBy(() -> book.rule("SEP-ZZZ"))
                .isInstanceOf(SeparationError.class)
                .hasMessageContaining("rule SEP-ZZZ is not filed");
    }

    @Test
    void theHouseBookFilesFourRules() {
        SeparationBook book = houseRules();
        assertThat(book.size()).isEqualTo(4);
        assertThat(book.rule("SEP-ADV").minutes()).isEqualTo(30);
        assertThat(book.rule("SEP-CAT-BRK").scope()).isSameAs(Scope.BREAK);
        assertThat(book.rule("SEP-CART").scope()).isSameAs(Scope.HOUR);
    }

    @Test
    void aCleanPlacementReportsNothing() {
        SeparationBook book = houseRules();
        List<Candidate> neighbours = List.of(spot(COUCH, 7, 12, 2), spot(CHEV, 7, 42, 4));
        assertThat(book.allows(spot(FORD, 7, 25, 3), neighbours)).isTrue();
        assertThat(book.violations(spot(FORD, 7, 25, 3), neighbours)).isEmpty();
    }

    @Test
    void oneBadPlacementCanBreakMoreThanOneRuleAtOnce() {
        SeparationBook book = houseRules();
        List<Candidate> neighbours = List.of(spot(GROUP, 7, 22, 2, "C9001"));
        List<Violation> found = book.violations(spot(FORD, 7, 24, 2, "C9001"), neighbours);
        assertThat(found.stream().map(violation -> violation.rule().code()).sorted())
                .containsExactly("SEP-CART", "SEP-CAT-BRK", "SEP-CAT-MIN");
    }

    @Test
    void aViolationSaysHowFarApartTheyWereWhenTheRuleCountsMinutes() {
        SeparationBook book = houseRules();
        List<Candidate> neighbours = List.of(spot(FORD, 7, 10, 2));
        List<Violation> found = book.violations(spot(FORD, 7, 25, 3), neighbours);
        // Fifteen minutes apart is far enough for the fifteen minute category rule, which
        // reads its bound as at least, so only the half hour rule fires.
        assertThat(found).extracting(violation -> violation.rule().code()).containsExactly("SEP-ADV");
        Violation advertiser = found.get(0);
        assertThat(advertiser.gap()).contains(Duration.ofSeconds(900));
        assertThat(advertiser.toString()).contains("15:00 apart");
    }

    @Test
    void aBreakScopedViolationHasNoGapToReport() {
        SeparationBook book = houseRules();
        List<Violation> found = book.violations(spot(FORD, 7, 20, 2), List.of(spot(GROUP, 7, 20, 2)));
        List<Violation> breaks = found.stream()
                .filter(violation -> violation.rule().scope() == Scope.BREAK)
                .toList();
        assertThat(breaks.get(0).gap()).isEmpty();
        assertThat(breaks.get(0).toString()).endsWith("DEALG at 7:20:00a");
    }

    @Test
    void aCandidateIsNeverCheckedAgainstItself() {
        SeparationBook book = houseRules();
        Candidate candidate = spot(FORD, 7, 20, 2, "C1204");
        assertThat(book.allows(candidate, List.of(candidate))).isTrue();
    }
}
