package io.github.rkaushikdev.cartwall.billing;

import static io.github.rkaushikdev.cartwall.Money.ZERO;
import static io.github.rkaushikdev.cartwall.billing.Invoice.runningTotal;
import static io.github.rkaushikdev.cartwall.billing.Invoice.summarise;
import static io.github.rkaushikdev.cartwall.domain.SpotLength.SIXTY;
import static io.github.rkaushikdev.cartwall.domain.SpotLength.THIRTY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.github.rkaushikdev.cartwall.BroadcastMonth;
import io.github.rkaushikdev.cartwall.Money;
import io.github.rkaushikdev.cartwall.domain.SpotLength;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class InvoiceTest {

    private static final BroadcastMonth AUGUST = new BroadcastMonth(2025, 8);
    private static final LocalDate MONDAY = LocalDate.of(2025, 8, 4);
    private static final LocalDate TUESDAY = LocalDate.of(2025, 8, 5);
    private static final Map<Integer, LineHeading> LINES =
            Map.of(1, new LineHeading("KWLO", "AMD"), 2, new LineHeading("KCRR", "MID"));

    static Detail aDetail(LocalDate day, int line, String rate, SpotLength length, boolean moved) {
        return new Detail(day, "7:10:00a", line, length, Money.parse(rate), "C1204", moved);
    }

    static Detail aDetail(LocalDate day, int line, String rate) {
        return aDetail(day, line, rate, THIRTY, false);
    }

    static Detail aDetail() {
        return aDetail(MONDAY, 1, "60.00");
    }

    static Invoice anInvoice(List<Detail> details, String rate, String number, String payer) {
        Invoice invoice = new Invoice(
                number, "25-1044", "HYVEE", payer, AUGUST, new BigDecimal(rate), details, List.of(), Optional.empty());
        return invoice.withSummaries(summarise(invoice.details(), LINES));
    }

    static Invoice anInvoice(List<Detail> details, String rate) {
        return anInvoice(details, rate, "25-1001", "HYVEE");
    }

    static Invoice anInvoice(List<Detail> details) {
        return anInvoice(details, "0");
    }

    @Test
    void anInvoiceThatCannotBeRaised() {
        assertThatThrownBy(() -> Invoice.empty("", "25-1044", "H", "H", AUGUST))
                .isInstanceOf(InvoiceError.class)
                .hasMessageContaining("needs a number");
        assertThatThrownBy(() -> Invoice.empty("25-1001", "", "H", "H", AUGUST))
                .isInstanceOf(InvoiceError.class)
                .hasMessageContaining("names no order");
        assertThatThrownBy(() -> new Invoice(
                        "25-1001", "25-1044", "H", "H", AUGUST, BigDecimal.ONE, List.of(), List.of(), Optional.empty()))
                .isInstanceOf(InvoiceError.class)
                .hasMessageContaining("not a commission");
    }

    @Test
    void theGrossIsTheSpotsAtTheRateOnTheOrder() {
        Invoice invoice = anInvoice(List.of(aDetail(), aDetail(TUESDAY, 1, "60.00")));
        assertThat(invoice.spots()).isEqualTo(2);
        assertThat(invoice.gross()).isEqualTo(Money.parse("120.00"));
    }

    @Test
    void directBusinessTakesNoCommission() {
        Invoice invoice = anInvoice(List.of(aDetail()));
        assertThat(invoice.commission()).isEqualTo(ZERO);
        assertThat(invoice.net()).isEqualTo(invoice.gross());
    }

    @Test
    void agencyBusinessTakesFifteenPercentOffTheGross() {
        Invoice invoice = anInvoice(List.of(aDetail()), "0.15");
        assertThat(invoice.gross()).isEqualTo(Money.parse("60.00"));
        assertThat(invoice.commission()).isEqualTo(Money.parse("9.00"));
        assertThat(invoice.net()).isEqualTo(Money.parse("51.00"));
    }

    @Test
    void theCommissionComesOffTheGrossAndNotOffEachSpot() {
        // A cent of rounding on every one of thirteen spots would show here.
        Invoice invoice = anInvoice(Collections.nCopies(13, aDetail(MONDAY, 1, "0.07")), "0.15");
        assertThat(invoice.gross()).isEqualTo(Money.parse("0.91"));
        assertThat(invoice.commission()).isEqualTo(Money.parse("0.14"));
        assertThat(invoice.net()).isEqualTo(Money.parse("0.77"));
    }

    @Test
    void anInvoiceWithNothingOnItIsWorthNothing() {
        Invoice invoice = Invoice.empty("25-1001", "25-1044", "HYVEE", "HYVEE", AUGUST);
        assertThat(invoice.isEmpty()).isTrue();
        assertThat(invoice.spots()).isZero();
        assertThat(invoice.gross()).isEqualTo(ZERO);
        assertThat(invoice.net()).isEqualTo(ZERO);
    }

    @Test
    void theDetailCarriesTheTimeItReallyRan() {
        Detail detail = new Detail(MONDAY, "7:10:04a", 1, THIRTY, Money.parse("60.00"), "C1204", false);
        assertThat(detail.at()).isEqualTo("7:10:04a");
        assertThat(detail).hasToString("08/04 7:10:04a :30 $60.00");
    }

    @Test
    void aMovedSpotIsMarkedAndStillBilled() {
        Invoice invoice = anInvoice(List.of(aDetail(MONDAY, 1, "60.00", THIRTY, true)));
        assertThat(invoice.details().get(0).moved()).isTrue();
        assertThat(invoice.gross()).isEqualTo(Money.parse("60.00"));
    }

    @Test
    void theSummaryRollsTheDetailUpByLine() {
        Invoice invoice = anInvoice(List.of(
                aDetail(MONDAY, 1, "60.00"), aDetail(TUESDAY, 1, "60.00"), aDetail(MONDAY, 2, "22.00", SIXTY, false)));
        assertThat(invoice.summaries()).extracting(Summary::line).containsExactly(1, 2);
        Summary first = invoice.summaryFor(1).orElseThrow();
        assertThat(first.spots()).isEqualTo(2);
        assertThat(first.gross()).isEqualTo(Money.parse("120.00"));
        assertThat(first.station()).isEqualTo("KWLO");
        assertThat(first.daypart()).isEqualTo("AMD");
        Summary second = invoice.summaryFor(2).orElseThrow();
        assertThat(second.length()).isEqualTo(SIXTY);
        assertThat(second.gross()).isEqualTo(Money.parse("22.00"));
        assertThat(invoice.summaryFor(9)).isEmpty();
    }

    @Test
    void aSummaryReadsTheWayTheSellerReadsIt() {
        Summary summary = new Summary(1, "KWLO", "AMD", THIRTY, Money.dollars(60), 12);
        assertThat(summary).hasToString("line 1 KWLO AMD 12 at $60.00");
        assertThat(summary.gross()).isEqualTo(Money.dollars(720));
    }

    @Test
    void twoRatesOnOneLineInOneMonthIsAnErrorNotAnAverage() {
        List<Detail> details = List.of(aDetail(MONDAY, 1, "60.00"), aDetail(MONDAY, 1, "55.00"));
        assertThatThrownBy(() -> summarise(details, Map.of()))
                .isInstanceOf(InvoiceError.class)
                .hasMessageContaining("different rates on one invoice");
    }

    @Test
    void aLineNobodyNamedAStationForStillSummarises() {
        Summary summary =
                summarise(List.of(aDetail(MONDAY, 7, "60.00")), Map.of()).get(0);
        assertThat(summary.line()).isEqualTo(7);
        assertThat(summary.station()).isEmpty();
    }

    @Test
    void theSummaryAndTheDetailHaveToAgree() {
        Invoice invoice = anInvoice(List.of(aDetail(), aDetail(TUESDAY, 1, "60.00")));
        invoice.checkAddsUp();
        Invoice wrong = invoice.withSummaries(List.of(new Summary(1, "KWLO", "AMD", THIRTY, Money.dollars(60), 1)));
        InvoiceError caught = catchThrowableOfType(InvoiceError.class, wrong::checkAddsUp);
        assertThat(caught).hasMessageContaining("details come to $120.00");
        assertThat(caught.context()).containsEntry("summary", "$60.00");
    }

    @Test
    void aSummaryThatCountsADifferentNumberOfSpotsIsCaught() {
        Invoice invoice = anInvoice(List.of(aDetail()))
                .withSummaries(List.of(new Summary(1, "KWLO", "AMD", THIRTY, Money.dollars(30), 2)));
        assertThatThrownBy(invoice::checkAddsUp)
                .isInstanceOf(InvoiceError.class)
                .hasMessageContaining("1 spots in detail and 2 in the summary");
    }

    @Test
    void anInvoiceListsTheDaysItCoversOnceEach() {
        Invoice invoice = anInvoice(
                List.of(aDetail(TUESDAY, 1, "60.00"), aDetail(MONDAY, 1, "60.00"), aDetail(MONDAY, 1, "60.00")));
        assertThat(invoice.days()).containsExactly(MONDAY, TUESDAY);
    }

    @Test
    void anInvoiceFindsTheDetailOfOneLine() {
        Invoice invoice = anInvoice(
                List.of(aDetail(MONDAY, 1, "60.00"), aDetail(MONDAY, 2, "22.00"), aDetail(MONDAY, 1, "60.00")));
        assertThat(invoice.forLine(1)).hasSize(2);
        assertThat(invoice.forLine(2)).hasSize(1);
        assertThat(invoice.forLine(9)).isEmpty();
    }

    @Test
    void anInvoiceReadsAsASentence() {
        assertThat(anInvoice(List.of(aDetail()), "0.15", "25-1001", "MOP"))
                .hasToString("25-1001 MOP 2025-08 1 spots $51.00");
    }

    @Test
    void aBatchTotalsGrossCommissionAndNet() {
        List<Invoice> batch = List.of(
                anInvoice(List.of(aDetail()), "0.15"),
                anInvoice(List.of(aDetail(), aDetail()), "0", "25-1002", "HYVEE"));
        Totals totals = runningTotal(batch);
        assertThat(totals.gross()).isEqualTo(Money.parse("180.00"));
        assertThat(totals.commission()).isEqualTo(Money.parse("9.00"));
        assertThat(totals.net()).isEqualTo(Money.parse("171.00"));
    }

    @Test
    void anEmptyBatchTotalsNothing() {
        assertThat(runningTotal(List.of())).isEqualTo(new Totals(ZERO, ZERO, ZERO));
    }
}
