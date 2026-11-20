package io.github.rkaushikdev.cartwall.persistence.models;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.cartwall.AirTime;
import io.github.rkaushikdev.cartwall.Duration;
import io.github.rkaushikdev.cartwall.Money;
import io.github.rkaushikdev.cartwall.domain.Band;
import io.github.rkaushikdev.cartwall.domain.BreakKind;
import io.github.rkaushikdev.cartwall.domain.Service;
import io.github.rkaushikdev.cartwall.domain.Source;
import io.github.rkaushikdev.cartwall.persistence.Columns;
import io.github.rkaushikdev.cartwall.persistence.Database;
import io.github.rkaushikdev.cartwall.persistence.EnumValue;
import io.github.rkaushikdev.cartwall.persistence.TestDatabase;
import io.github.rkaushikdev.cartwall.persistence.UnitOfWork;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ReferenceModelsTest {

    private static final LocalDate JANUARY = LocalDate.of(2025, 1, 1);

    private Database database;
    private UnitOfWork session;
    private EntityManager em;

    @BeforeEach
    void openASession() {
        database = TestDatabase.fresh();
        session = database.unitOfWork();
        em = session.entityManager();
    }

    @AfterEach
    void closeTheSession() {
        session.close();
    }

    static StationRow aStation(String call) {
        return new StationRow(call, Band.FM, new BigDecimal("99.1"), "Waterloo", Service.MUSIC, "Waterloo Cedar Falls");
    }

    static StationRow aStation() {
        return aStation("KWLO");
    }

    static DaypartRow drive(String station, String name) {
        return new DaypartRow(station, "AMD", name, "M-F", 6, 10);
    }

    static BreakRow local(int number, Duration position) {
        return new BreakRow(number, BreakKind.LOCAL, position, Duration.ofSeconds(180), 6);
    }

    static AssignmentRow anAssignment(int ordinal, String label) {
        return new AssignmentRow("KWLO", JANUARY, ordinal, label, "M-Su", 0, 24, "MUS", Source.LOCAL);
    }

    private <T> List<T> all(Class<T> type) {
        return em.createQuery("select x from " + type.getSimpleName() + " x", type)
                .getResultList();
    }

    @Test
    void everyConstraintCarriesANameWeChose() throws SQLException {
        Set<String> names = new TreeSet<>();
        try (Connection connection = database.dataSource().getConnection();
                Statement statement = connection.createStatement();
                ResultSet rows =
                        statement.executeQuery("select constraint_name from information_schema.table_constraints"
                                + " where table_schema = 'PUBLIC' and table_name <> 'flyway_schema_history'")) {
            while (rows.next()) {
                names.add(rows.getString(1).toLowerCase(Locale.ROOT));
            }
        }
        assertThat(names).contains("pk_station", "uq_daypart_station_code");
        assertThat(names).allMatch(name -> !name.startsWith("constraint_"));
    }

    @Test
    void moneyGoesInAsCentsAndComesBackAsMoney() {
        Columns.Cents column = new Columns.Cents();
        assertThat(column.convertToDatabaseColumn(new Money(12550))).isEqualTo(12550);
        assertThat(column.convertToEntityAttribute(12550)).isEqualTo(new Money(12550));
        assertThat(column.convertToDatabaseColumn(null)).isNull();
        assertThat(column.convertToEntityAttribute(null)).isNull();
    }

    @Test
    void lengthsGoInAsTenths() {
        Columns.Tenths column = new Columns.Tenths();
        assertThat(column.convertToDatabaseColumn(new Duration(305))).isEqualTo(305);
        assertThat(column.convertToEntityAttribute(305)).isEqualTo(new Duration(305));
        assertThat(column.convertToDatabaseColumn(null)).isNull();
    }

    @Test
    void airTimesGoInAsTenthsFromMidnightWithNoTimezone() {
        Columns.ClockTenths column = new Columns.ClockTenths();
        assertThat(column.convertToDatabaseColumn(AirTime.at(7, 10))).isEqualTo(258000);
        assertThat(column.convertToEntityAttribute(258000)).isEqualTo(AirTime.at(7, 10));
        assertThat(column.convertToEntityAttribute(null)).isNull();
    }

    @Test
    void anEnumIsStoredAsItsShortString() {
        EnumValue.BreakKinds column = new EnumValue.BreakKinds();
        assertThat(column.convertToDatabaseColumn(BreakKind.LEGAL_ID)).isEqualTo("legal id");
        assertThat(column.convertToEntityAttribute("legal id")).isSameAs(BreakKind.LEGAL_ID);
        assertThat(column.convertToDatabaseColumn(null)).isNull();
    }

    @Test
    void aStationRoundTripsWithItsEnumerationsIntact() {
        StationRow station = aStation();
        station.setCommercialLoad(18);
        em.persist(station);
        session.commit();
        em.clear();
        StationRow found = em.find(StationRow.class, "KWLO");
        assertThat(found.getBand()).isSameAs(Band.FM);
        assertThat(found.getService()).isSameAs(Service.MUSIC);
        assertThat(found.getCommercialLoad()).isEqualTo(18);
        assertThat(found).hasToString("<StationRow KWLO>");
    }

    @Test
    void aTranslatorPointsAtTheStationItCarries() {
        em.persist(aStation("KWLO"));
        StationRow translator = aStation("KWLO-LP");
        translator.setBand(Band.TRANSLATOR);
        translator.setCarries("KWLO");
        em.persist(translator);
        session.commit();
        em.clear();
        assertThat(em.find(StationRow.class, "KWLO-LP").getCarries()).isEqualTo("KWLO");
    }

    @Test
    void aTranslatorCannotCarryAStationThatIsNotThere() {
        StationRow translator = aStation("KWLO-LP");
        translator.setBand(Band.TRANSLATOR);
        translator.setCarries("NOPE");
        em.persist(translator);
        assertThatThrownBy(session::commit).isInstanceOf(PersistenceException.class);
    }

    @Test
    void theDatabaseIsToldToEnforceForeignKeys() {
        assertThatThrownBy(() -> {
                    em.persist(drive("NOPE", "drive"));
                    session.commit();
                })
                .isInstanceOf(PersistenceException.class);
    }

    @Test
    void aStationMayNotFileTheSameDaypartCodeTwice() {
        em.persist(aStation());
        em.persist(drive("KWLO", "drive"));
        session.commit();
        assertThatThrownBy(() -> {
                    em.persist(drive("KWLO", "again"));
                    session.commit();
                })
                .isInstanceOf(PersistenceException.class);
    }

    @Test
    void twoStationsMayUseTheSameDaypartCode() {
        em.persist(aStation("KWLO"));
        StationRow other = aStation("KCRR");
        other.setFrequency(new BigDecimal("97.7"));
        em.persist(other);
        em.persist(drive("KWLO", "drive"));
        em.persist(drive("KCRR", "drive"));
        session.commit();
        assertThat(all(DaypartRow.class)).hasSize(2);
    }

    @Test
    void aClockCarriesItsBreaksInNumberOrder() {
        em.persist(aStation());
        ClockRow clock = new ClockRow("KWLO", "MUS", "music hour", JANUARY);
        clock.setBreaks(List.of(
                local(3, Duration.ofSeconds(1320)),
                new BreakRow(1, BreakKind.LEGAL_ID, new Duration(0), Duration.ofSeconds(10), 1, false),
                local(2, Duration.ofSeconds(600))));
        em.persist(clock);
        session.commit();
        em.clear();
        ClockRow found = all(ClockRow.class).get(0);
        assertThat(found.getBreaks()).extracting(BreakRow::getNumber).containsExactly(1, 2, 3);
        assertThat(found.getBreaks().get(0).getKind()).isSameAs(BreakKind.LEGAL_ID);
        assertThat(found.getBreaks().get(1).getPosition()).isEqualTo(Duration.ofSeconds(600));
    }

    @Test
    void aClockMayNotNumberABreakTwice() {
        em.persist(aStation());
        ClockRow clock = new ClockRow("KWLO", "MUS", "music hour", JANUARY);
        clock.setBreaks(List.of(local(2, new Duration(0)), local(2, Duration.ofSeconds(600))));
        assertThatThrownBy(() -> {
                    em.persist(clock);
                    session.commit();
                })
                .isInstanceOf(PersistenceException.class);
    }

    @Test
    void theSameClockCodeMayBeRefiledForALaterDate() {
        em.persist(aStation());
        em.persist(new ClockRow("KWLO", "MUS", "music", JANUARY));
        em.persist(new ClockRow("KWLO", "MUS", "music", LocalDate.of(2025, 7, 1)));
        session.commit();
        assertThat(all(ClockRow.class)).hasSize(2);
    }

    @Test
    void theSameClockCodeMayNotBeFiledTwiceForOneDate() {
        em.persist(aStation());
        em.persist(new ClockRow("KWLO", "MUS", "music", JANUARY));
        assertThatThrownBy(() -> {
                    em.persist(new ClockRow("KWLO", "MUS", "again", JANUARY));
                    session.commit();
                })
                .isInstanceOf(PersistenceException.class);
    }

    @Test
    void deletingAClockTakesItsBreaksWithIt() {
        em.persist(aStation());
        ClockRow clock = new ClockRow("KWLO", "MUS", "music", JANUARY);
        clock.setBreaks(List.of(local(2, new Duration(0))));
        em.persist(clock);
        session.commit();
        em.remove(clock);
        session.commit();
        assertThat(all(BreakRow.class)).isEmpty();
    }

    @Test
    void assignmentsKeepTheOrderTheyWereFiledIn() {
        em.persist(aStation());
        em.persist(anAssignment(1, "all week"));
        em.persist(new AssignmentRow("KWLO", JANUARY, 2, "football", "F", 19, 23, "", Source.PREEMPTED));
        session.commit();
        em.clear();
        List<AssignmentRow> rows = em.createQuery(
                        "select a from AssignmentRow a order by a.ordinal", AssignmentRow.class)
                .getResultList();
        assertThat(rows).extracting(AssignmentRow::getLabel).containsExactly("all week", "football");
        assertThat(rows.get(1).getSource()).isSameAs(Source.PREEMPTED);
        assertThat(rows.get(1).getClockCode()).isEmpty();
    }

    @Test
    void twoAssignmentsMayNotClaimTheSamePlaceInTheStack() {
        em.persist(aStation());
        em.persist(anAssignment(1, "first"));
        assertThatThrownBy(() -> {
                    em.persist(anAssignment(1, "second"));
                    session.commit();
                })
                .isInstanceOf(PersistenceException.class);
    }
}
