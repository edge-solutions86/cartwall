package io.github.rkaushikdev.cartwall.services;

import static io.github.rkaushikdev.cartwall.domain.SpotLength.THIRTY;
import static io.github.rkaushikdev.cartwall.services.Crediting.byReason;
import static io.github.rkaushikdev.cartwall.services.Crediting.creditTheRebate;
import static io.github.rkaushikdev.cartwall.services.Crediting.creditsAgainst;
import static io.github.rkaushikdev.cartwall.services.Crediting.outstanding;
import static io.github.rkaushikdev.cartwall.services.Crediting.raiseCredit;
import static io.github.rkaushikdev.cartwall.services.Crediting.standing;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.github.rkaushikdev.cartwall.Fixtures;
import io.github.rkaushikdev.cartwall.LookupFailure;
import io.github.rkaushikdev.cartwall.Money;
import io.github.rkaushikdev.cartwall.domain.ClassOfTime;
import io.github.rkaushikdev.cartwall.domain.Flight;
import io.github.rkaushikdev.cartwall.domain.Line;
import io.github.rkaushikdev.cartwall.domain.Order;
import io.github.rkaushikdev.cartwall.persistence.Database;
import io.github.rkaushikdev.cartwall.persistence.TestDatabase;
import io.github.rkaushikdev.cartwall.persistence.UnitOfWork;
import io.github.rkaushikdev.cartwall.persistence.models.CreditRow;
import io.github.rkaushikdev.cartwall.persistence.models.InvoiceRow;
import io.github.rkaushikdev.cartwall.persistence.models.Reason;
import io.github.rkaushikdev.cartwall.services.Crediting.Standing;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CreditingTest {

    private static final ClassOfTime FIXED = ClassOfTime.FIXED;
    private static final LocalDate MONDAY = LocalDate.of(2025, 8, 4);
    private static final LocalDate RAISED = LocalDate.of(2025, 8, 25);

    private Database engine;

    @BeforeEach
    void putTwoOrdersOnFile() {
        engine = TestDatabase.fresh();
        try (UnitOfWork uow = engine.unitOfWork()) {
            uow.stations().saveCluster(Fixtures.cluster());
            uow.accounts().saveBook(Fixtures.accounts());
            for (String[] pair : List.of(new String[] {"25-1044", "HYVEE"}, new String[] {"25-2000", "DEALF"})) {
                uow.orders()
                        .save(new Order(
                                pair[0],
                                pair[1],
                                LocalDate.of(2025, 7, 21),
                                "DHOLM",
                                List.of(new Line(
                                        1,
                                        "KWLO",
                                        "AMD",
                                        FIXED,
                                        THIRTY,
                                        Money.dollars(60),
                                        Flight.build(MONDAY, LocalDate.of(2025, 8, 8), "M-F", 5)))));
            }
            uow.commit();
        }
    }

    private static InvoiceRow anInvoice(
            UnitOfWork uow,
            String number,
            String order,
            String advertiser,
            String payer,
            Money gross,
            BigDecimal rate,
            int month) {
        InvoiceRow row = new InvoiceRow(
                number,
                order,
                advertiser,
                payer,
                2025,
                month,
                RAISED,
                gross,
                gross.scale(rate),
                gross.minus(gross.scale(rate)));
        row.setSpots(5);
        row.setCommissionRate(rate);
        uow.entityManager().persist(row);
        uow.entityManager().flush();
        return row;
    }

    private static InvoiceRow anInvoice(UnitOfWork uow, String number, Money gross, int month) {
        return anInvoice(uow, number, "25-1044", "HYVEE", "HYVEE", gross, BigDecimal.ZERO, month);
    }

    private static InvoiceRow anInvoice(UnitOfWork uow) {
        return anInvoice(uow, "25-1001", Money.dollars(300), 8);
    }

    @Test
    void anInvoiceWithNoCreditsOwesAllOfIt() {
        Standing found;
        try (UnitOfWork uow = engine.unitOfWork()) {
            anInvoice(uow);
            found = standing(uow, "25-1001");
        }
        assertThat(found.billed()).isEqualTo(Money.dollars(300));
        assertThat(found.credited()).isEqualTo(new Money(0));
        assertThat(found.owing()).isEqualTo(Money.dollars(300));
        assertThat(found.isSettled()).isFalse();
        assertThat(found).hasToString("25-1001 billed $300.00 less $0.00 leaves $300.00");
    }

    @Test
    void aCreditComesOffWhatIsOwing() {
        try (UnitOfWork uow = engine.unitOfWork()) {
            anInvoice(uow);
            raiseCredit(uow, "25-1001", Money.dollars(60), Reason.DID_NOT_RUN, RAISED, "", 1);
            assertThat(standing(uow, "25-1001").owing()).isEqualTo(Money.dollars(240));
        }
    }

    @Test
    void aCreditTakesANumberOffItsOwnCounter() {
        try (UnitOfWork uow = engine.unitOfWork()) {
            anInvoice(uow);
            CreditRow first = raiseCredit(uow, "25-1001", Money.dollars(60), Reason.DID_NOT_RUN, RAISED);
            CreditRow second = raiseCredit(uow, "25-1001", Money.dollars(60), Reason.DID_NOT_RUN, RAISED);
            assertThat(List.of(first.getNumber(), second.getNumber())).containsExactly("25-1001", "25-1002");
            // The invoice series is untouched by the credit series.
            assertThat(new Numbering(uow.entityManager()).peek("INV", 2025)).isEqualTo(1000);
        }
    }

    @Test
    void aCreditOnAnAgencyInvoiceTakesTheCommissionOffToo() {
        CreditRow credit;
        try (UnitOfWork uow = engine.unitOfWork()) {
            anInvoice(uow, "25-1001", "25-2000", "DEALF", "MOP", Money.dollars(300), new BigDecimal("0.15"), 8);
            credit = raiseCredit(uow, "25-1001", Money.dollars(60), Reason.DID_NOT_RUN, RAISED);
        }
        assertThat(credit.getGross()).isEqualTo(Money.dollars(60));
        assertThat(credit.getCommission()).isEqualTo(Money.dollars(9));
        assertThat(credit.getNet()).isEqualTo(Money.dollars(51));
        assertThat(credit.getPayer()).isEqualTo("MOP");
    }

    @Test
    void aCreditRecordsWhatItWasFor() {
        CreditRow credit;
        try (UnitOfWork uow = engine.unitOfWork()) {
            anInvoice(uow);
            credit = raiseCredit(uow, "25-1001", Money.dollars(60), Reason.WRONG_DAYPART, RAISED, "", 1);
        }
        assertThat(credit.getReason()).isSameAs(Reason.WRONG_DAYPART);
        assertThat(credit.getSpots()).isEqualTo(1);
        assertThat(credit.getNote()).isEmpty();
        assertThat(credit.isSent()).isFalse();
        assertThat(credit).hasToString("<CreditRow 25-1001 against 25-1001>");
    }

    @Test
    void aCreditForSomeOtherReasonNeedsANote() {
        try (UnitOfWork uow = engine.unitOfWork()) {
            anInvoice(uow);
            assertThatThrownBy(() -> raiseCredit(uow, "25-1001", Money.dollars(60), Reason.OTHER, RAISED))
                    .isInstanceOf(CreditError.class)
                    .hasMessageContaining("needs a note");
            CreditRow credit = raiseCredit(uow, "25-1001", Money.dollars(60), Reason.OTHER, RAISED, " goodwill ", 0);
            assertThat(credit.getNote()).isEqualTo("goodwill");
        }
    }

    @Test
    void aCreditOfNothingOrLessIsNotACredit() {
        try (UnitOfWork uow = engine.unitOfWork()) {
            anInvoice(uow);
            for (Money amount : List.of(new Money(0), new Money(-100))) {
                assertThatThrownBy(() -> raiseCredit(uow, "25-1001", amount, Reason.DID_NOT_RUN, RAISED))
                        .isInstanceOf(CreditError.class)
                        .hasMessageContaining("is not a credit");
            }
        }
    }

    @Test
    void aCreditAgainstAnInvoiceThatIsNotOnFile() {
        try (UnitOfWork uow = engine.unitOfWork()) {
            assertThatThrownBy(() -> raiseCredit(uow, "25-9999", Money.dollars(60), Reason.DID_NOT_RUN, RAISED))
                    .isInstanceOf(LookupFailure.class)
                    .hasMessageContaining("invoice 25-9999 is not on file");
            assertThatThrownBy(() -> standing(uow, "25-9999"))
                    .isInstanceOf(LookupFailure.class)
                    .hasMessageContaining("is not on file");
        }
    }

    @Test
    void moreThanIsLeftIsRefusedAndSaysWhatIsLeft() {
        OverCredited caught;
        try (UnitOfWork uow = engine.unitOfWork()) {
            anInvoice(uow);
            raiseCredit(uow, "25-1001", Money.dollars(250), Reason.DID_NOT_RUN, RAISED);
            caught = catchThrowableOfType(
                    OverCredited.class,
                    () -> raiseCredit(uow, "25-1001", Money.dollars(60), Reason.DID_NOT_RUN, RAISED));
        }
        assertThat(caught).hasMessageContaining("has $50.00 left");
        assertThat(caught.context()).containsEntry("left", "$50.00");
        assertThat(caught.context()).containsEntry("asked", "$60.00");
    }

    @Test
    void creditingTheWholeInvoiceSettlesIt() {
        try (UnitOfWork uow = engine.unitOfWork()) {
            anInvoice(uow);
            raiseCredit(uow, "25-1001", Money.dollars(300), Reason.DID_NOT_RUN, RAISED);
            assertThat(standing(uow, "25-1001").isSettled()).isTrue();
        }
    }

    @Test
    void theCreditsAgainstAnInvoiceComeBackInNumberOrder() {
        try (UnitOfWork uow = engine.unitOfWork()) {
            anInvoice(uow);
            raiseCredit(uow, "25-1001", Money.dollars(60), Reason.DID_NOT_RUN, RAISED);
            raiseCredit(uow, "25-1001", Money.dollars(60), Reason.RATE_ERROR, RAISED);
            List<CreditRow> found = creditsAgainst(uow, "25-1001");
            assertThat(found).extracting(CreditRow::getNumber).containsExactly("25-1001", "25-1002");
            assertThat(found).extracting(CreditRow::getReason).containsExactly(Reason.DID_NOT_RUN, Reason.RATE_ERROR);
        }
    }

    @Test
    void aPoliticalRebateHasItsOwnReasonAndNeedsNoNote() {
        CreditRow credit;
        try (UnitOfWork uow = engine.unitOfWork()) {
            anInvoice(uow);
            credit = creditTheRebate(uow, "25-1001", Money.dollars(18), RAISED);
        }
        assertThat(credit.getReason()).isSameAs(Reason.POLITICAL_REBATE);
        assertThat(credit.getGross()).isEqualTo(Money.dollars(18));
    }

    @Test
    void theMonthEndGroupsCreditsByReasonWorstFirst() {
        Map<String, Money> tally;
        try (UnitOfWork uow = engine.unitOfWork()) {
            anInvoice(uow);
            raiseCredit(uow, "25-1001", Money.dollars(30), Reason.DID_NOT_RUN, RAISED);
            raiseCredit(uow, "25-1001", Money.dollars(90), Reason.RATE_ERROR, RAISED);
            raiseCredit(uow, "25-1001", Money.dollars(20), Reason.DID_NOT_RUN, RAISED);
            tally = byReason(uow, MONDAY, LocalDate.of(2025, 8, 31));
        }
        assertThat(tally.keySet()).containsExactly("rate error", "did not run");
        assertThat(tally).containsEntry("rate error", Money.dollars(90));
        assertThat(tally).containsEntry("did not run", Money.dollars(50));
    }

    @Test
    void aCreditOutsideTheSpanIsNotCounted() {
        try (UnitOfWork uow = engine.unitOfWork()) {
            anInvoice(uow);
            raiseCredit(uow, "25-1001", Money.dollars(30), Reason.DID_NOT_RUN, LocalDate.of(2025, 9, 2));
            assertThat(byReason(uow, MONDAY, LocalDate.of(2025, 8, 31))).isEmpty();
        }
    }

    @Test
    void aSpanThatRunsBackwardsIsRefused() {
        try (UnitOfWork uow = engine.unitOfWork()) {
            assertThatThrownBy(() -> byReason(uow, LocalDate.of(2025, 8, 31), MONDAY))
                    .isInstanceOf(CreditError.class)
                    .hasMessageContaining("runs backwards");
        }
    }

    @Test
    void theOutstandingListIsWhatIsStillOwingMostFirst() {
        List<Standing> found;
        try (UnitOfWork uow = engine.unitOfWork()) {
            anInvoice(uow, "25-1001", Money.dollars(300), 8);
            anInvoice(uow, "25-1002", "25-2000", "DEALF", "DEALF", Money.dollars(500), BigDecimal.ZERO, 8);
            // A September invoice for the same order. One invoice per order a month.
            anInvoice(uow, "25-1003", Money.dollars(100), 9);
            raiseCredit(uow, "25-1003", Money.dollars(100), Reason.DID_NOT_RUN, RAISED);
            found = outstanding(uow);
        }
        assertThat(found).extracting(Standing::invoice).containsExactly("25-1002", "25-1001");
        assertThat(found.get(0).owing()).isEqualTo(Money.dollars(500));
    }
}
