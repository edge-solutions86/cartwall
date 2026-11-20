package io.github.rkaushikdev.cartwall.persistence.models;

import static io.github.rkaushikdev.cartwall.domain.SpotLength.SIXTY;
import static io.github.rkaushikdev.cartwall.domain.SpotLength.THIRTY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.cartwall.Duration;
import io.github.rkaushikdev.cartwall.Money;
import io.github.rkaushikdev.cartwall.domain.Band;
import io.github.rkaushikdev.cartwall.domain.ClassOfTime;
import io.github.rkaushikdev.cartwall.domain.OrderStatus;
import io.github.rkaushikdev.cartwall.domain.RotationKind;
import io.github.rkaushikdev.cartwall.domain.Service;
import io.github.rkaushikdev.cartwall.domain.SpotLength;
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

class TradingModelsTest {

    private static final ClassOfTime FIXED = ClassOfTime.FIXED;
    private static final ClassOfTime NOTICE = ClassOfTime.NOTICE;
    private static final LocalDate MONDAY = LocalDate.of(2025, 8, 4);
    private static final LocalDate FRIDAY = LocalDate.of(2025, 8, 29);

    private UnitOfWork session;
    private EntityManager em;

    @BeforeEach
    void openASessionOnAStationAndAnAccount() {
        session = TestDatabase.fresh().unitOfWork();
        em = session.entityManager();
        em.persist(new StationRow("KWLO", Band.FM, new BigDecimal("99.1"), "Waterloo", Service.MUSIC, "WCF"));
        em.persist(new AgencyRow("MOP", "Mopar Media", new BigDecimal("0.15"), false));
        em.persist(new AdvertiserRow("HYVEE", "Hy-Vee"));
        session.commit();
    }

    @AfterEach
    void closeTheSession() {
        session.close();
    }

    /** A line with the usual defaults, overridable one at a time. */
    private record ALine(
            int number,
            String daypart,
            ClassOfTime grade,
            SpotLength length,
            Money rate,
            String cardCode,
            String days,
            int perWeek) {

        static ALine number(int number) {
            return new ALine(number, "AMD", FIXED, THIRTY, Money.dollars(60), "KWLO-25", "M-F", 12);
        }

        ALine daypart(String value) {
            return new ALine(number, value, grade, length, rate, cardCode, days, perWeek);
        }

        ALine grade(ClassOfTime value) {
            return new ALine(number, daypart, value, length, rate, cardCode, days, perWeek);
        }

        ALine length(SpotLength value) {
            return new ALine(number, daypart, grade, value, rate, cardCode, days, perWeek);
        }

        ALine rate(Money value) {
            return new ALine(number, daypart, grade, length, value, cardCode, days, perWeek);
        }

        ALine cardCode(String value) {
            return new ALine(number, daypart, grade, length, rate, value, days, perWeek);
        }

        ALine days(String value) {
            return new ALine(number, daypart, grade, length, rate, cardCode, value, perWeek);
        }

        ALine perWeek(int value) {
            return new ALine(number, daypart, grade, length, rate, cardCode, days, value);
        }

        LineRow row() {
            LineRow row = new LineRow(number, "KWLO", daypart, grade, length, rate, MONDAY, FRIDAY, days, perWeek);
            row.setCardCode(cardCode);
            return row;
        }
    }

    private static LineRow aLine(int number) {
        return ALine.number(number).row();
    }

    private static LineRow aLine() {
        return aLine(1);
    }

    private OrderRow anOrder(String number, String advertiser, String agency, List<LineRow> lines) {
        OrderRow order = new OrderRow(number, advertiser, LocalDate.of(2025, 7, 21), "DHOLM");
        order.setAgency(agency);
        order.setLines(lines);
        em.persist(order);
        return order;
    }

    private OrderRow anOrder(List<LineRow> lines) {
        return anOrder("25-1044", "HYVEE", null, lines);
    }

    private OrderRow anOrder() {
        return anOrder(List.of(aLine()));
    }

    private static CutRow aCut(String cart, String isci) {
        return new CutRow(cart, isci, "HYVEE", THIRTY, new Duration(300), MONDAY, null, false);
    }

    private static CutRow aCut(String cart) {
        return aCut(cart, "HYVE2501");
    }

    private <T> List<T> all(Class<T> type) {
        return em.createQuery("select x from " + type.getSimpleName() + " x", type)
                .getResultList();
    }

    private LineRow theOnlyLine() {
        return em.createQuery("select x from LineRow x", LineRow.class).getSingleResult();
    }

    @Test
    void anOrderStartsPendingAndRoundTrips() {
        anOrder();
        session.commit();
        em.clear();
        OrderRow found = em.find(OrderRow.class, "25-1044");
        assertThat(found.getStatus()).isSameAs(OrderStatus.PENDING);
        assertThat(found.getSalesperson()).isEqualTo("DHOLM");
        assertThat(found.getCampaign()).isEmpty();
        assertThat(found).hasToString("<OrderRow 25-1044>");
    }

    @Test
    void anOrderCannotNameAnAdvertiserThatIsNotOnFile() {
        assertThatThrownBy(() -> {
                    anOrder("25-1044", "NOPE", null, List.of(aLine()));
                    session.commit();
                })
                .isInstanceOf(PersistenceException.class);
    }

    @Test
    void anOrderCannotNameAnAgencyThatIsNotOnFile() {
        assertThatThrownBy(() -> {
                    anOrder("25-1044", "HYVEE", "NOPE", List.of(aLine()));
                    session.commit();
                })
                .isInstanceOf(PersistenceException.class);
    }

    @Test
    void theLinesComeBackInLineNumberOrder() {
        anOrder(List.of(aLine(3), aLine(1), aLine(2)));
        session.commit();
        em.clear();
        OrderRow found = em.find(OrderRow.class, "25-1044");
        assertThat(found.getLines()).extracting(LineRow::getNumber).containsExactly(1, 2, 3);
    }

    @Test
    void anOrderMayNotNumberALineTwice() {
        assertThatThrownBy(() -> {
                    anOrder(List.of(aLine(1), ALine.number(1).daypart("MID").row()));
                    session.commit();
                })
                .isInstanceOf(PersistenceException.class);
    }

    @Test
    void twoOrdersMayBothHaveALineOne() {
        anOrder("25-1044", "HYVEE", null, List.of(aLine()));
        anOrder("25-1045", "HYVEE", null, List.of(aLine()));
        session.commit();
        assertThat(all(LineRow.class)).hasSize(2);
    }

    @Test
    void theFlightLivesOnTheLineRatherThanInItsOwnTable() {
        anOrder(List.of(ALine.number(1).days("Sa,Su").perWeek(4).row()));
        session.commit();
        em.clear();
        LineRow line = theOnlyLine();
        assertThat(line.getDays()).isEqualTo("Sa,Su");
        assertThat(line.getPerWeek()).isEqualTo(4);
        assertThat(line.getFirstAir()).isEqualTo(MONDAY);
        assertThat(line.getLastAir()).isEqualTo(FRIDAY);
    }

    @Test
    void theRateIsStoredOnTheLineWithTheCardItCameOff() {
        anOrder(List.of(
                ALine.number(1).rate(Money.dollars(60)).cardCode("KWLO-25").row()));
        session.commit();
        em.clear();
        LineRow line = theOnlyLine();
        assertThat(line.getRate()).isEqualTo(new Money(6000));
        assertThat(line.getCardCode()).isEqualTo("KWLO-25");
        assertThat(line.getGrade()).isSameAs(FIXED);
        assertThat(line.getLength()).isEqualTo(THIRTY);
    }

    @Test
    void aSixtySecondLineKeepsItsLength() {
        anOrder(List.of(ALine.number(1).length(SIXTY).grade(NOTICE).row()));
        session.commit();
        em.clear();
        LineRow line = theOnlyLine();
        assertThat(line.getLength()).isEqualTo(SIXTY);
        assertThat(line.getGrade()).isSameAs(NOTICE);
    }

    @Test
    void deletingAnOrderTakesItsLinesWithIt() {
        OrderRow order =
                anOrder(List.of(aLine(1), ALine.number(2).daypart("MID").row()));
        session.commit();
        em.remove(order);
        session.commit();
        assertThat(all(LineRow.class)).isEmpty();
    }

    @Test
    void aLineCarriesItsRotationInTheOrderItWasWritten() {
        em.persist(aCut("C1204"));
        em.persist(aCut("C1205", "HYVE2502"));
        em.persist(aCut("C1206", "HYVE2503"));
        LineRow line = aLine();
        line.setRotation(List.of(
                new RotationRow(3, "C1206"),
                new RotationRow(1, "C1204", 2, RotationKind.WEIGHTED),
                new RotationRow(2, "C1205", 1, RotationKind.WEIGHTED)));
        anOrder(List.of(line));
        session.commit();
        em.clear();
        LineRow found = theOnlyLine();
        assertThat(found.getRotation()).extracting(RotationRow::getCart).containsExactly("C1204", "C1205", "C1206");
        assertThat(found.getRotation().get(0).getWeight()).isEqualTo(2);
        assertThat(found.getRotation().get(0).getKind()).isSameAs(RotationKind.WEIGHTED);
        assertThat(found.getRotation().get(0).toString()).startsWith("<RotationRow");
    }

    @Test
    void aRotationCannotCarryTheSameCartTwice() {
        em.persist(aCut("C1204"));
        LineRow line = aLine();
        line.setRotation(List.of(new RotationRow(1, "C1204"), new RotationRow(2, "C1204")));
        assertThatThrownBy(() -> {
                    anOrder(List.of(line));
                    session.commit();
                })
                .isInstanceOf(PersistenceException.class);
    }

    @Test
    void aRotationCannotNameACartNobodyDubbed() {
        LineRow line = aLine();
        line.setRotation(List.of(new RotationRow(1, "C9999")));
        assertThatThrownBy(() -> {
                    anOrder(List.of(line));
                    session.commit();
                })
                .isInstanceOf(PersistenceException.class);
    }

    @Test
    void deletingALineTakesItsRotationWithIt() {
        em.persist(aCut("C1204"));
        LineRow line = aLine();
        line.setRotation(List.of(new RotationRow(1, "C1204")));
        OrderRow order = anOrder(List.of(line));
        session.commit();
        order.setLines(List.of());
        session.commit();
        assertThat(all(RotationRow.class)).isEmpty();
    }

    @Test
    void confirmingAnOrderIsAStatusChangeAndNothingElse() {
        OrderRow order = anOrder();
        session.commit();
        order.setStatus(OrderStatus.CONFIRMED);
        session.commit();
        em.clear();
        OrderRow found = em.find(OrderRow.class, "25-1044");
        assertThat(found.getStatus()).isSameAs(OrderStatus.CONFIRMED);
        assertThat(found.getLines()).hasSize(1);
    }
}
