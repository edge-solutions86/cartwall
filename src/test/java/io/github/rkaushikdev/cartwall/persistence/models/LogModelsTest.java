package io.github.rkaushikdev.cartwall.persistence.models;

import static io.github.rkaushikdev.cartwall.domain.SpotLength.SIXTY;
import static io.github.rkaushikdev.cartwall.domain.SpotLength.THIRTY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.cartwall.AirTime;
import io.github.rkaushikdev.cartwall.domain.Band;
import io.github.rkaushikdev.cartwall.domain.ClassOfTime;
import io.github.rkaushikdev.cartwall.domain.Service;
import io.github.rkaushikdev.cartwall.domain.SpotLength;
import io.github.rkaushikdev.cartwall.logs.LogState;
import io.github.rkaushikdev.cartwall.persistence.TestDatabase;
import io.github.rkaushikdev.cartwall.persistence.UnitOfWork;
import io.github.rkaushikdev.cartwall.scheduling.LineKey;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LogModelsTest {

    private static final ClassOfTime FIXED = ClassOfTime.FIXED;
    private static final ClassOfTime IMMEDIATE = ClassOfTime.IMMEDIATE;
    private static final LocalDate MONDAY = LocalDate.of(2025, 8, 4);
    private static final LocalDateTime SIGNED = LocalDateTime.of(2025, 8, 1, 16, 30);

    private UnitOfWork session;
    private EntityManager em;

    @BeforeEach
    void openASessionOnAStationAndTwoAccounts() {
        session = TestDatabase.fresh().unitOfWork();
        em = session.entityManager();
        em.persist(new StationRow("KWLO", Band.FM, new BigDecimal("99.1"), "Waterloo", Service.MUSIC, "WCF"));
        em.persist(new AdvertiserRow("HYVEE", "Hy-Vee"));
        em.persist(new AdvertiserRow("SLUMB", "Slumberland"));
        session.commit();
    }

    @AfterEach
    void closeTheSession() {
        session.close();
    }

    /** A spot with the usual defaults, overridable one at a time. */
    private record ASpot(
            int hour,
            int number,
            int position,
            AirTime at,
            int line,
            String advertiser,
            SpotLength length,
            ClassOfTime grade,
            String cart,
            boolean fixed) {

        static ASpot line(int line) {
            return new ASpot(7, 2, 0, null, line, "HYVEE", THIRTY, FIXED, "C1204", false);
        }

        ASpot at(int newHour, int newNumber, int newPosition) {
            return new ASpot(newHour, newNumber, newPosition, at, line, advertiser, length, grade, cart, fixed);
        }

        ASpot position(int value) {
            return at(hour, number, value);
        }

        ASpot airing(AirTime value) {
            return new ASpot(hour, number, position, value, line, advertiser, length, grade, cart, fixed);
        }

        ASpot advertiser(String value) {
            return new ASpot(hour, number, position, at, line, value, length, grade, cart, fixed);
        }

        ASpot carrying(SpotLength newLength, ClassOfTime newGrade, String newCart, boolean newFixed) {
            return new ASpot(hour, number, position, at, line, advertiser, newLength, newGrade, newCart, newFixed);
        }

        SpotRow row() {
            AirTime airing = at != null ? at : AirTime.at(hour, 10);
            return new SpotRow(hour, number, position, airing, "25-1044", line, advertiser, length, grade, cart, fixed);
        }
    }

    private static SpotRow aSpot() {
        return ASpot.line(1).row();
    }

    private LogRow aLog(LocalDate day, List<SpotRow> spots) {
        LogRow log = new LogRow("KWLO", day);
        log.setSpots(spots);
        em.persist(log);
        return log;
    }

    private LogRow aLog(List<SpotRow> spots) {
        return aLog(MONDAY, spots);
    }

    private LogRow aLog() {
        return aLog(List.of());
    }

    private static BumpRow aBump(LocalDate day, String byOrder) {
        return new BumpRow("KWLO", day, 7, 2, "25-0100", 3, "SLUMB", THIRTY, IMMEDIATE, byOrder, 1);
    }

    private static BumpRow aBump() {
        return aBump(MONDAY, "25-9000");
    }

    private <T> List<T> all(Class<T> type) {
        return em.createQuery("select x from " + type.getSimpleName() + " x", type)
                .getResultList();
    }

    private <T> T theOnly(Class<T> type) {
        return em.createQuery("select x from " + type.getSimpleName() + " x", type)
                .getSingleResult();
    }

    @Test
    void aLogStartsInDraftAtRevisionZero() {
        aLog();
        session.commit();
        em.clear();
        LogRow found = theOnly(LogRow.class);
        assertThat(found.getState()).isSameAs(LogState.DRAFT);
        assertThat(found.getRevision()).isZero();
        assertThat(found.getApprovedBy()).isEmpty();
        assertThat(found.getApprovedAt()).isNull();
        assertThat(found).hasToString("<LogRow KWLO 2025-08-04 rev 0>");
    }

    @Test
    void signingALogRecordsThePersonAndTheMoment() {
        LogRow log = aLog();
        session.commit();
        log.setState(LogState.APPROVED);
        log.setApprovedBy("T. Ronan");
        log.setApprovedAt(SIGNED);
        session.commit();
        em.clear();
        LogRow found = theOnly(LogRow.class);
        assertThat(found.getState()).isSameAs(LogState.APPROVED);
        assertThat(found.getApprovedBy()).isEqualTo("T. Ronan");
        assertThat(found.getApprovedAt()).isEqualTo(SIGNED);
    }

    @Test
    void oneStationGetsOneLogPerDay() {
        assertThatThrownBy(() -> {
                    aLog();
                    aLog();
                    session.commit();
                })
                .isInstanceOf(PersistenceException.class);
    }

    @Test
    void aStationGetsALogForEveryDay() {
        aLog(MONDAY, List.of());
        aLog(LocalDate.of(2025, 8, 5), List.of());
        session.commit();
        assertThat(all(LogRow.class)).hasSize(2);
    }

    @Test
    void theSpotsComeBackInAirOrderWhateverTheQueryPlanThinks() {
        aLog(List.of(
                ASpot.line(3).at(9, 4, 0).row(),
                ASpot.line(2).at(7, 2, 1).row(),
                ASpot.line(1).at(7, 2, 0).row(),
                ASpot.line(4).at(7, 5, 0).row()));
        session.commit();
        em.clear();
        LogRow found = theOnly(LogRow.class);
        assertThat(found.getSpots()).extracting(SpotRow::getLine).containsExactly(1, 2, 4, 3);
    }

    @Test
    void twoSpotsCannotHoldTheSamePlaceInABreak() {
        assertThatThrownBy(() -> {
                    aLog(List.of(
                            ASpot.line(1).position(0).row(),
                            ASpot.line(2).position(0).row()));
                    session.commit();
                })
                .isInstanceOf(PersistenceException.class);
    }

    @Test
    void theAirTimeIsStoredAndNotRecomputed() {
        aLog(List.of(ASpot.line(1).airing(AirTime.at(7, 10, 30)).row()));
        session.commit();
        em.clear();
        SpotRow spot = theOnly(SpotRow.class);
        assertThat(spot.getAt()).isEqualTo(AirTime.at(7, 10, 30));
        assertThat(spot.key()).isEqualTo(new LineKey("25-1044", 1));
        assertThat(spot).hasToString("<SpotRow 25-1044-1 07:10:30>");
    }

    @Test
    void aSpotKeepsItsLengthGradeAndCart() {
        aLog(List.of(ASpot.line(1).carrying(SIXTY, IMMEDIATE, "C1205", true).row()));
        session.commit();
        em.clear();
        SpotRow spot = theOnly(SpotRow.class);
        assertThat(spot.getLength()).isEqualTo(SIXTY);
        assertThat(spot.getGrade()).isSameAs(IMMEDIATE);
        assertThat(spot.getCart()).isEqualTo("C1205");
        assertThat(spot.isFixed()).isTrue();
    }

    @Test
    void aSpotCannotNameAnAdvertiserThatIsNotOnFile() {
        assertThatThrownBy(() -> {
                    aLog(List.of(ASpot.line(1).advertiser("NOPE").row()));
                    session.commit();
                })
                .isInstanceOf(PersistenceException.class);
    }

    @Test
    void deletingALogTakesItsSpotsWithIt() {
        LogRow log = aLog(List.of(ASpot.line(1).row(), ASpot.line(2).position(1).row()));
        session.commit();
        em.remove(log);
        session.commit();
        assertThat(all(SpotRow.class)).isEmpty();
    }

    @Test
    void aBumpRecordsWhatCameOffAndWhoTookIt() {
        em.persist(aBump());
        session.commit();
        BumpRow found = theOnly(BumpRow.class);
        assertThat(found.key()).isEqualTo(new LineKey("25-0100", 3));
        assertThat(found.getByOrder()).isEqualTo("25-9000");
        assertThat(found.isMadeGood()).isFalse();
        assertThat(found).hasToString("<BumpRow 25-0100-3 off 2025-08-04>");
    }

    @Test
    void aBumpIsNotAFlagOnTheSpotItTookOff() {
        aLog(List.of(aSpot()));
        em.persist(aBump(MONDAY, "25-1044"));
        session.commit();
        // The log holds one spot. The bump table holds the debt, separately.
        assertThat(all(SpotRow.class)).hasSize(1);
        assertThat(all(BumpRow.class)).hasSize(1);
    }

    @Test
    void aBumpCanBeMarkedMadeGoodWithoutTouchingTheLog() {
        BumpRow bump = aBump();
        em.persist(bump);
        session.commit();
        bump.setMadeGood(true);
        session.commit();
        em.clear();
        assertThat(theOnly(BumpRow.class).isMadeGood()).isTrue();
    }

    @Test
    void oneLineCanBeBumpedMoreThanOnce() {
        for (LocalDate day : List.of(MONDAY, LocalDate.of(2025, 8, 5))) {
            em.persist(aBump(day, "25-9000"));
        }
        session.commit();
        List<BumpRow> owed = em.createQuery("select b from BumpRow b where b.orderNumber = '25-0100'", BumpRow.class)
                .getResultList();
        assertThat(owed).hasSize(2);
    }
}
