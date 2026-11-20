package io.github.rkaushikdev.cartwall.reports;

import static io.github.rkaushikdev.cartwall.Money.ZERO;
import static io.github.rkaushikdev.cartwall.reports.Statement.worstFirst;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.github.rkaushikdev.cartwall.Money;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class StatementTest {

    private static final LocalDate TODAY = LocalDate.of(2025, 11, 3);

    static Item anItem(String invoice, int daysOld, String billed, String credited) {
        return new Item(invoice, "25-1044", TODAY.minusDays(daysOld), Money.parse(billed), Money.parse(credited));
    }

    static Item anItem(String invoice, int daysOld, String billed) {
        return anItem(invoice, daysOld, billed, "0.00");
    }

    static Item anItem(String invoice) {
        return anItem(invoice, 0, "300.00");
    }

    static Statement aStatement(List<Item> items, String payer) {
        return new Statement(payer, TODAY, items);
    }

    static Statement aStatement(List<Item> items) {
        return aStatement(items, "MOP");
    }

    @Test
    void aBucketOpensAtItsOwnDayCount() {
        assertThat(Bucket.of(0)).isSameAs(Bucket.CURRENT);
        assertThat(Bucket.of(29)).isSameAs(Bucket.CURRENT);
        assertThat(Bucket.of(30)).isSameAs(Bucket.THIRTY);
        assertThat(Bucket.of(59)).isSameAs(Bucket.THIRTY);
        assertThat(Bucket.of(60)).isSameAs(Bucket.SIXTY);
        assertThat(Bucket.of(89)).isSameAs(Bucket.SIXTY);
        assertThat(Bucket.of(90)).isSameAs(Bucket.NINETY);
        assertThat(Bucket.of(400)).isSameAs(Bucket.NINETY);
    }

    @Test
    void theBucketsReadTheWayTheOfficeSaysThem() {
        assertThat(Bucket.values())
                .extracting(Bucket::label)
                .containsExactly("current", "30 days", "60 days", "90 plus");
    }

    @Test
    void anItemOwesWhatIsLeftAfterItsCredits() {
        assertThat(anItem("25-1001", 0, "300.00", "60.00").owing()).isEqualTo(Money.dollars(240));
    }

    @Test
    void anItemIsAgedFromTheDayItWasRaised() {
        assertThat(anItem("25-1001", 45, "300.00").age(TODAY)).isEqualTo(45);
        assertThat(anItem("25-1001", 45, "300.00").bucket(TODAY)).isSameAs(Bucket.THIRTY);
    }

    @Test
    void anInvoiceRaisedAfterTheStatementDateIsRefused() {
        Item item = new Item("25-1001", "25-1044", LocalDate.of(2025, 12, 1), Money.dollars(300), ZERO);
        StatementError caught = catchThrowableOfType(StatementError.class, () -> item.age(TODAY));
        assertThat(caught).hasMessageContaining("after 2025-11-03");
        assertThat(caught.context()).containsEntry("invoice", "25-1001");
    }

    @Test
    void aStatementTotalsWhatIsOpen() {
        Statement statement = aStatement(List.of(anItem("25-1001"), anItem("25-1002", 0, "200.00")));
        assertThat(statement.total()).isEqualTo(Money.dollars(500));
        assertThat(statement.isClear()).isFalse();
    }

    @Test
    void aFullyCreditedInvoiceDropsOffTheList() {
        Statement statement = aStatement(List.of(anItem("25-1001", 0, "300.00", "300.00"), anItem("25-1002")));
        assertThat(statement.openItems()).extracting(Item::invoice).containsExactly("25-1002");
        assertThat(statement.total()).isEqualTo(Money.dollars(300));
    }

    @Test
    void aStatementWithNothingOpenIsClear() {
        Statement statement = aStatement(List.of(anItem("25-1001", 0, "300.00", "300.00")));
        assertThat(statement.isClear()).isTrue();
        assertThat(statement.openItems()).isEmpty();
        assertThat(statement.oldest()).isEmpty();
    }

    @Test
    void theOpenItemsComeBackOldestFirst() {
        Statement statement = aStatement(List.of(
                anItem("25-1003", 10, "300.00"), anItem("25-1001", 95, "300.00"), anItem("25-1002", 40, "300.00")));
        assertThat(statement.openItems()).extracting(Item::invoice).containsExactly("25-1001", "25-1002", "25-1003");
        assertThat(statement.oldest().orElseThrow().invoice()).isEqualTo("25-1001");
    }

    @Test
    void theAgingAlwaysCarriesAllFourBuckets() {
        Map<Bucket, Money> aging =
                aStatement(List.of(anItem("25-1001", 5, "300.00"))).aging();
        assertThat(aging.keySet()).containsExactly(Bucket.values());
        assertThat(aging.get(Bucket.CURRENT)).isEqualTo(Money.dollars(300));
        assertThat(aging.get(Bucket.NINETY)).isEqualTo(ZERO);
    }

    @Test
    void eachInvoiceLandsInTheBucketItsAgeSays() {
        Map<Bucket, Money> aging = aStatement(List.of(
                        anItem("25-1001", 5, "100.00"),
                        anItem("25-1002", 35, "200.00"),
                        anItem("25-1003", 65, "400.00"),
                        anItem("25-1004", 120, "800.00")))
                .aging();
        assertThat(aging.get(Bucket.CURRENT)).isEqualTo(Money.dollars(100));
        assertThat(aging.get(Bucket.THIRTY)).isEqualTo(Money.dollars(200));
        assertThat(aging.get(Bucket.SIXTY)).isEqualTo(Money.dollars(400));
        assertThat(aging.get(Bucket.NINETY)).isEqualTo(Money.dollars(800));
    }

    @Test
    void aCreditWrittenTodayDoesNotMakeOldMoneyCurrent() {
        Map<Bucket, Money> aging =
                aStatement(List.of(anItem("25-1001", 95, "300.00", "100.00"))).aging();
        assertThat(aging.get(Bucket.NINETY)).isEqualTo(Money.dollars(200));
        assertThat(aging.get(Bucket.CURRENT)).isEqualTo(ZERO);
    }

    @Test
    void whatIsPastDueIsEverythingOlderThanTheLine() {
        Statement statement = aStatement(List.of(
                anItem("25-1001", 5, "100.00"), anItem("25-1002", 35, "200.00"), anItem("25-1003", 95, "400.00")));
        assertThat(statement.pastDue(30)).isEqualTo(Money.dollars(600));
        assertThat(statement.pastDue(60)).isEqualTo(Money.dollars(400));
        assertThat(statement.pastDue(120)).isEqualTo(ZERO);
    }

    @Test
    void aStatementReadsAsASentence() {
        assertThat(aStatement(List.of(anItem("25-1001"), anItem("25-1002", 0, "200.00"))))
                .hasToString("MOP as of 11/03/2025: $500.00 on 2");
    }

    @Test
    void aRenderedStatementPutsTheAgingAtTheFoot() {
        String text = aStatement(List.of(anItem("25-1001", 5, "100.00"), anItem("25-1002", 95, "400.00")))
                .render();
        assertThat(text)
                .contains("STATEMENT   MOP")
                .contains("AS OF       11/03/2025")
                .contains("25-1002")
                .contains("TOTAL       $500.00")
                .contains("current    $100.00")
                .contains("90 plus    $400.00")
                .contains("30 days    $0.00");
    }

    @Test
    void aClearStatementStillRenders() {
        String text =
                aStatement(List.of(anItem("25-1001", 0, "300.00", "300.00"))).render();
        assertThat(text).contains("nothing outstanding").contains("TOTAL       $0.00");
    }

    @Test
    void theMondaysListIsWorstPastDueFirst() {
        List<Statement> statements = List.of(
                aStatement(List.of(anItem("25-1001", 95, "100.00")), "MOP"),
                aStatement(List.of(anItem("25-1001", 95, "900.00")), "CRS"),
                aStatement(List.of(anItem("25-1001", 5, "900.00")), "HYVEE"));
        assertThat(worstFirst(statements, 60)).extracting(Statement::payer).containsExactly("CRS", "MOP");
    }

    @Test
    void nobodyPastDueMakesForAShortList() {
        assertThat(worstFirst(List.of(aStatement(List.of(anItem("25-1001", 5, "300.00")), "MOP")), 60))
                .isEmpty();
    }
}
