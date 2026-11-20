package io.github.rkaushikdev.cartwall.persistence.models;

import static io.github.rkaushikdev.cartwall.Fixtures.MONDAY;
import static io.github.rkaushikdev.cartwall.domain.SpotLength.SIXTY;
import static io.github.rkaushikdev.cartwall.domain.SpotLength.THIRTY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import io.github.rkaushikdev.cartwall.AirTime;
import io.github.rkaushikdev.cartwall.Fixtures;
import io.github.rkaushikdev.cartwall.Money;
import io.github.rkaushikdev.cartwall.domain.ClassOfTime;
import io.github.rkaushikdev.cartwall.persistence.AccountRepository;
import io.github.rkaushikdev.cartwall.persistence.OrderRepository;
import io.github.rkaushikdev.cartwall.persistence.StationRepository;
import io.github.rkaushikdev.cartwall.persistence.TestDatabase;
import io.github.rkaushikdev.cartwall.persistence.UnitOfWork;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BillingModelsTest {

    private static final ClassOfTime FIXED = ClassOfTime.FIXED;

    private UnitOfWork session;
    private EntityManager em;

    @BeforeEach
    void openASessionOnTheClusterTheBookAndOneOrder() {
        session = TestDatabase.fresh().unitOfWork();
        em = session.entityManager();
        new StationRepository(em).saveCluster(Fixtures.cluster());
        new AccountRepository(em).saveBook(Fixtures.accounts());
        new OrderRepository(em)
                .save(Fixtures.anOrder()
                        .number("25-1044")
                        .advertiser("HYVEE")
                        .written(LocalDate.of(2025, 7, 21))
                        .salesperson("DHOLM")
                        .lines(Fixtures.aLine(1)
                                .station("KWLO")
                                .daypart("AMD")
                                .grade(FIXED)
                                .length(THIRTY)
                                .rate(Money.dollars(60))
                                .flight(MONDAY, LocalDate.of(2025, 8, 29), "M-F", 12)
                                .build())
                        .build());
        session.commit();
    }

    @AfterEach
    void closeTheSession() {
        session.close();
    }

    /** An invoice with the usual defaults, overridable one at a time. */
    private record AnInvoice(
            String number,
            String order,
            String payer,
            int month,
            LocalDate raised,
            BigDecimal rate,
            Money commission,
            Money net) {

        static AnInvoice number(String number) {
            return new AnInvoice(
                    number,
                    "25-1044",
                    "HYVEE",
                    8,
                    LocalDate.of(2025, 8, 25),
                    new BigDecimal("0"),
                    new Money(0),
                    Money.dollars(120));
        }

        AnInvoice order(String value) {
            return new AnInvoice(number, value, payer, month, raised, rate, commission, net);
        }

        AnInvoice month(int value) {
            return new AnInvoice(number, order, payer, value, raised, rate, commission, net);
        }

        AnInvoice raised(LocalDate value) {
            return new AnInvoice(number, order, payer, month, value, rate, commission, net);
        }

        AnInvoice commissioned(BigDecimal newRate, Money newCommission, Money newNet, String newPayer) {
            return new AnInvoice(number, order, newPayer, month, raised, newRate, newCommission, newNet);
        }

        InvoiceRow row() {
            InvoiceRow row = new InvoiceRow(
                    number, order, "HYVEE", payer, 2025, month, raised, Money.dollars(120), commission, net);
            row.setSpots(2);
            row.setCommissionRate(rate);
            return row;
        }
    }

    private static InvoiceRow anInvoice() {
        return AnInvoice.number("25-1001").row();
    }

    private static InvoiceDetailRow aDetail(LocalDate day, AirTime at) {
        return new InvoiceDetailRow(day, at, 1, THIRTY, Money.dollars(60), "C1204", false);
    }

    private static InvoiceSummaryRow aSummary(int line, String station, String daypart, int spots, Money gross) {
        return new InvoiceSummaryRow(line, station, daypart, THIRTY, Money.dollars(60), spots, gross);
    }

    private <T> List<T> all(Class<T> type) {
        return em.createQuery("select x from " + type.getSimpleName() + " x", type)
                .getResultList();
    }

    @Test
    void anInvoiceRoundTripsWithItsMoneyStoredNotDerived() {
        em.persist(AnInvoice.number("25-1001")
                .commissioned(new BigDecimal("0.15"), Money.dollars(18), Money.dollars(102), "MOP")
                .row());
        session.commit();
        em.clear();
        InvoiceRow found = em.find(InvoiceRow.class, "25-1001");
        assertThat(found.getGross()).isEqualTo(Money.dollars(120));
        assertThat(found.getCommission()).isEqualTo(Money.dollars(18));
        assertThat(found.getNet()).isEqualTo(Money.dollars(102));
        assertThat(found.getCommissionRate()).isEqualByComparingTo("0.1500");
        assertThat(found.getPayer()).isEqualTo("MOP");
        assertThat(found.isSent()).isFalse();
        assertThat(found).hasToString("<InvoiceRow 25-1001 2025-08>");
    }

    @Test
    void theBroadcastMonthIsStoredAsAYearAndAMonth() {
        em.persist(AnInvoice.number("25-1001").month(8).row());
        session.commit();
        InvoiceRow found = em.find(InvoiceRow.class, "25-1001");
        assertThat(List.of(found.getYear(), found.getMonth())).containsExactly(2025, 8);
    }

    @Test
    void oneOrderGetsOneInvoiceAMonth() {
        em.persist(AnInvoice.number("25-1001").month(8).row());
        session.commit();
        em.persist(AnInvoice.number("25-1002").month(8).row());
        assertThatThrownBy(session::commit).isInstanceOf(PersistenceException.class);
    }

    @Test
    void oneOrderGetsAnInvoiceEveryMonth() {
        em.persist(AnInvoice.number("25-1001").month(8).row());
        em.persist(AnInvoice.number("25-1002").month(9).row());
        session.commit();
        assertThat(all(InvoiceRow.class)).hasSize(2);
    }

    @Test
    void anInvoiceCannotNameAnOrderThatIsNotOnFile() {
        em.persist(AnInvoice.number("25-1001").order("25-9999").row());
        assertThatThrownBy(session::commit).isInstanceOf(PersistenceException.class);
    }

    @Test
    void theDetailComesBackInAirOrder() {
        InvoiceRow invoice = anInvoice();
        invoice.setDetails(List.of(
                aDetail(LocalDate.of(2025, 8, 5), AirTime.at(7, 10)),
                aDetail(MONDAY, AirTime.at(9, 38)),
                aDetail(MONDAY, AirTime.at(7, 10, 4))));
        em.persist(invoice);
        session.commit();
        em.clear();
        InvoiceRow found = em.find(InvoiceRow.class, "25-1001");
        assertThat(found.getDetails())
                .extracting(row -> row.getDay(), row -> row.getAt().hour())
                .containsExactly(tuple(MONDAY, 7), tuple(MONDAY, 9), tuple(LocalDate.of(2025, 8, 5), 7));
        assertThat(found.getDetails().get(0).getAt()).isEqualTo(AirTime.at(7, 10, 4));
    }

    @Test
    void aDetailLineRecordsWhetherTheSpotMoved() {
        InvoiceRow invoice = anInvoice();
        invoice.setDetails(
                List.of(new InvoiceDetailRow(MONDAY, AirTime.at(8, 22), 1, SIXTY, Money.dollars(105), "C1205", true)));
        em.persist(invoice);
        session.commit();
        InvoiceDetailRow row = all(InvoiceDetailRow.class).get(0);
        assertThat(row.isMoved()).isTrue();
        assertThat(row.getLength()).isEqualTo(SIXTY);
        assertThat(row.getRate()).isEqualTo(Money.dollars(105));
        assertThat(row.toString()).startsWith("<InvoiceDetailRow 25-1001");
    }

    @Test
    void theSummaryComesBackInLineOrder() {
        InvoiceRow invoice = anInvoice();
        invoice.setSummaries(List.of(
                new InvoiceSummaryRow(2, "KCRR", "MID", THIRTY, Money.dollars(22), 4, Money.dollars(88)),
                aSummary(1, "KWLO", "AMD", 2, Money.dollars(120))));
        em.persist(invoice);
        session.commit();
        em.clear();
        InvoiceRow found = em.find(InvoiceRow.class, "25-1001");
        assertThat(found.getSummaries()).extracting(InvoiceSummaryRow::getLine).containsExactly(1, 2);
        assertThat(found.getSummaries().get(0).getGross()).isEqualTo(Money.dollars(120));
        assertThat(found.getSummaries().get(0)).hasToString("<InvoiceSummaryRow 25-1001 line 1>");
    }

    @Test
    void aSummaryCannotCarryOneLineTwice() {
        InvoiceRow invoice = anInvoice();
        invoice.setSummaries(List.of(
                aSummary(1, "KWLO", "AMD", 1, Money.dollars(60)), aSummary(1, "KWLO", "MID", 1, Money.dollars(60))));
        assertThatThrownBy(() -> {
                    em.persist(invoice);
                    session.commit();
                })
                .isInstanceOf(PersistenceException.class);
    }

    @Test
    void deletingAnInvoiceTakesItsDetailAndSummaryWithIt() {
        InvoiceRow invoice = anInvoice();
        invoice.setDetails(List.of(aDetail(MONDAY, AirTime.at(7, 10))));
        invoice.setSummaries(List.of(aSummary(1, "KWLO", "AMD", 1, Money.dollars(60))));
        em.persist(invoice);
        session.commit();
        em.remove(invoice);
        session.commit();
        assertThat(all(InvoiceDetailRow.class)).isEmpty();
        assertThat(all(InvoiceSummaryRow.class)).isEmpty();
    }

    @Test
    void markingAnInvoiceSentDoesNotTouchTheMoney() {
        em.persist(anInvoice());
        session.commit();
        InvoiceRow invoice = em.find(InvoiceRow.class, "25-1001");
        invoice.setSent(true);
        session.commit();
        em.clear();
        InvoiceRow found = em.find(InvoiceRow.class, "25-1001");
        assertThat(found.isSent()).isTrue();
        assertThat(found.getGross()).isEqualTo(Money.dollars(120));
    }

    @Test
    void invoicesCanBeFoundByTheDayTheyWereRaised() {
        em.persist(AnInvoice.number("25-1001")
                .month(8)
                .raised(LocalDate.of(2025, 8, 25))
                .row());
        em.persist(AnInvoice.number("25-1002")
                .month(9)
                .raised(LocalDate.of(2025, 9, 29))
                .row());
        session.commit();
        List<String> found = em.createQuery("select i.number from InvoiceRow i where i.raised <= :last", String.class)
                .setParameter("last", LocalDate.of(2025, 8, 31))
                .getResultList();
        assertThat(found).containsExactly("25-1001");
    }
}
