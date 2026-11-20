package io.github.rkaushikdev.cartwall.reports;

import static io.github.rkaushikdev.cartwall.Money.ZERO;
import static io.github.rkaushikdev.cartwall.reports.Sellout.againstBudget;
import static io.github.rkaushikdev.cartwall.reports.Sellout.busiest;
import static io.github.rkaushikdev.cartwall.reports.Sellout.pace;
import static io.github.rkaushikdev.cartwall.reports.Sellout.rolledUp;
import static io.github.rkaushikdev.cartwall.reports.Sellout.thinnest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.assertj.core.api.Assertions.within;

import io.github.rkaushikdev.cartwall.Money;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class SelloutTest {

    private static final LocalDate MONDAY = LocalDate.of(2025, 8, 4);
    private static final LocalDate SUNDAY = LocalDate.of(2025, 8, 10);

    static Period aPeriod(String label, int units, int sold, String revenue, LocalDate first, LocalDate last) {
        return new Period(label, first, last, units, sold, Money.parse(revenue));
    }

    static Period aPeriod(String label, int units, int sold, String revenue) {
        return aPeriod(label, units, sold, revenue, MONDAY, SUNDAY);
    }

    static Period aPeriod() {
        return aPeriod("this week", 1000, 400, "8000.00");
    }

    @Test
    void aPeriodWorksOutItsOwnSellout() {
        Period period = aPeriod();
        assertThat(period.open()).isEqualTo(600);
        assertThat(period.sellout()).isEqualTo(0.4);
    }

    @Test
    void aPeriodWithNoInventoryDoesNotDivideByZero() {
        assertThat(aPeriod("this week", 0, 0, "0").sellout()).isZero();
    }

    @Test
    void aPeriodThatSoldMoreThanItHadIsRefused() {
        SelloutError caught = catchThrowableOfType(SelloutError.class, () -> aPeriod("this week", 100, 101, "8000.00"));
        assertThat(caught).hasMessageContaining("which is oversold");
        assertThat(caught.context()).containsEntry("sold", 101);
    }

    @Test
    void aPeriodThatRunsBackwardsIsRefused() {
        assertThatThrownBy(() -> aPeriod("this week", 1000, 400, "8000.00", SUNDAY, MONDAY))
                .isInstanceOf(SelloutError.class)
                .hasMessageContaining("runs backwards");
    }

    @Test
    void theAverageUnitRateIsRevenueOverWhatSold() {
        assertThat(aPeriod().rate()).isEqualTo(Money.dollars(20));
        assertThat(aPeriod("this week", 1000, 0, "0").rate()).isEqualTo(ZERO);
    }

    @Test
    void aPeriodReadsAsALineOfAReport() {
        assertThat(aPeriod().toString())
                .contains("this week")
                .contains("400 of 1000")
                .contains("40.0%")
                .contains("$8,000.00");
    }

    @Test
    void pacingReportsTheChangeInUnitsMoneyAndSellout() {
        Pace found = pace(aPeriod("this week", 1000, 500, "10000.00"), aPeriod("last week", 1000, 400, "8000.00"));
        assertThat(found.unitChange()).isEqualTo(100);
        assertThat(found.revenueChange()).isEqualTo(Money.dollars(2000));
        assertThat(found.selloutChange()).isCloseTo(0.1, within(1e-9));
        assertThat(found.comparable()).isTrue();
    }

    @Test
    void aWeekWithABallgameInItIsFlaggedAsNotComparable() {
        Pace found = pace(aPeriod("this week", 800, 400, "8000.00"), aPeriod("last week", 1000, 400, "8000.00"));
        assertThat(found.comparable()).isFalse();
        assertThat(found.unitChange()).isZero();
        assertThat(found.selloutChange()).isPositive();
        assertThat(found.toString()).contains("different inventory");
    }

    @Test
    void theIndexIsRevenueNowOverRevenueThen() {
        Pace found = pace(aPeriod("this week", 1000, 400, "10000.00"), aPeriod("last week", 1000, 400, "8000.00"));
        assertThat(found.index()).contains(1.25);
    }

    @Test
    void pacingAgainstAPeriodThatEarnedNothingHasNoIndex() {
        Pace found = pace(aPeriod("this week", 1000, 400, "10000.00"), aPeriod("last week", 1000, 0, "0"));
        assertThat(found.index()).isEmpty();
    }

    @Test
    void aPaceReadsAsASentence() {
        String printed = pace(aPeriod("this week", 1000, 500, "10000.00"), aPeriod("last week", 1000, 400, "8000.00"))
                .toString();
        assertThat(printed)
                .contains("this week against last week")
                .contains("+100 units")
                .contains("$2,000.00")
                .contains("+10.0%");
    }

    @Test
    void aPaceThatWentBackwardsSaysSoWithASign() {
        String printed = pace(aPeriod("this week", 1000, 300, "6000.00"), aPeriod("last week", 1000, 400, "8000.00"))
                .toString();
        assertThat(printed).contains("-100 units").contains("-$2,000.00");
    }

    @Test
    void budgetReportsTheGapAndTheFraction() {
        BudgetGap found = againstBudget(aPeriod(), Money.dollars(10000));
        assertThat(found.gap()).isEqualTo(Money.dollars(-2000));
        assertThat(found.fraction()).contains(0.8);
    }

    @Test
    void aBudgetOfNothingHasNoFractionToReport() {
        BudgetGap found = againstBudget(aPeriod(), ZERO);
        assertThat(found.gap()).isEqualTo(Money.dollars(8000));
        assertThat(found.fraction()).isEmpty();
    }

    @Test
    void theFullestAndThinnestAreBySelloutAndNotByUnits() {
        List<Period> weeks = List.of(
                aPeriod("week one", 1000, 400, "8000.00"),
                aPeriod("week two", 500, 300, "6000.00"),
                aPeriod("week three", 1000, 200, "4000.00"));
        assertThat(busiest(weeks).orElseThrow().label()).isEqualTo("week two");
        assertThat(thinnest(weeks).orElseThrow().label()).isEqualTo("week three");
    }

    @Test
    void aPeriodWithNoInventoryIsLeftOutOfTheComparison() {
        List<Period> weeks = List.of(aPeriod("off air", 0, 0, "0"), aPeriod("on air", 100, 10, "200.00"));
        assertThat(busiest(weeks).orElseThrow().label()).isEqualTo("on air");
        assertThat(thinnest(weeks).orElseThrow().label()).isEqualTo("on air");
        assertThat(busiest(List.of())).isEmpty();
        assertThat(thinnest(List.of())).isEmpty();
    }

    @Test
    void rollingSeveralPeriodsUpAddsTheDenominatorsToo() {
        List<Period> weeks = List.of(
                aPeriod("week one", 1000, 400, "8000.00", MONDAY, SUNDAY),
                aPeriod("week two", 800, 500, "12000.00", LocalDate.of(2025, 8, 11), LocalDate.of(2025, 8, 17)));
        Period month = rolledUp(weeks, "August");
        assertThat(month.units()).isEqualTo(1800);
        assertThat(month.sold()).isEqualTo(900);
        assertThat(month.revenue()).isEqualTo(Money.dollars(20000));
        assertThat(month.first()).isEqualTo(MONDAY);
        assertThat(month.last()).isEqualTo(LocalDate.of(2025, 8, 17));
        assertThat(month.sellout()).isEqualTo(0.5);
    }

    @Test
    void rollingUpNothingIsRefused() {
        assertThatThrownBy(() -> rolledUp(List.of(), "August"))
                .isInstanceOf(SelloutError.class)
                .hasMessageContaining("nothing to roll up");
    }
}
