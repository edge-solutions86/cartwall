package io.github.rkaushikdev.cartwall.services;

import static io.github.rkaushikdev.cartwall.HouseLog.Field.field;
import static io.github.rkaushikdev.cartwall.services.Audit.NIGHTLY;
import static io.github.rkaushikdev.cartwall.services.Audit.about;
import static io.github.rkaushikdev.cartwall.services.Audit.byActor;
import static io.github.rkaushikdev.cartwall.services.Audit.count;
import static io.github.rkaushikdev.cartwall.services.Audit.onDay;
import static io.github.rkaushikdev.cartwall.services.Audit.record;
import static io.github.rkaushikdev.cartwall.services.Audit.tryRecord;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.cartwall.HouseLog;
import io.github.rkaushikdev.cartwall.persistence.Database;
import io.github.rkaushikdev.cartwall.persistence.TestDatabase;
import io.github.rkaushikdev.cartwall.persistence.UnitOfWork;
import io.github.rkaushikdev.cartwall.persistence.models.AuditRow;
import jakarta.persistence.EntityManager;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AuditTest {

    private static final LocalDate MONDAY = LocalDate.of(2025, 8, 4);
    private static final LocalDateTime NINE = LocalDateTime.of(2025, 8, 4, 9, 0, 0);
    private static final LocalDateTime TEN = LocalDateTime.of(2025, 8, 4, 10, 0, 0);

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

    private AuditRow theOnlyRow() {
        return em.createQuery("select a from AuditRow a", AuditRow.class).getSingleResult();
    }

    private int foreignKeysOn(String table) throws SQLException {
        try (Connection connection = database.dataSource().getConnection();
                PreparedStatement statement =
                        connection.prepareStatement("select count(*) from information_schema.table_constraints"
                                + " where table_schema = 'PUBLIC' and constraint_type = 'FOREIGN KEY'"
                                + " and table_name = ?")) {
            statement.setString(1, table);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getInt(1);
            }
        }
    }

    @Test
    void anEntryRecordsWhoDidWhatToWhichDocument() {
        record(em, "order.confirmed", "DHOLM", "order", "25-1044", NINE, field("value", "$2,880.00"));
        session.commit();
        AuditRow row = theOnlyRow();
        assertThat(row.getAction()).isEqualTo("order.confirmed");
        assertThat(row.getActor()).isEqualTo("DHOLM");
        assertThat(row.getKind()).isEqualTo("order");
        assertThat(row.getReference()).isEqualTo("25-1044");
        assertThat(row.getDetail()).isEqualTo("value=$2,880.00");
        assertThat(row).hasToString("<AuditRow order.confirmed order:25-1044>");
    }

    @Test
    void anEntryWithNoDetailIsStillAnEntry() {
        record(em, "log.released", NIGHTLY, "log", "KWLO 2025-08-04", NINE);
        session.commit();
        assertThat(theOnlyRow().getDetail()).isEmpty();
    }

    @Test
    void anEntryNobodyCouldFindAgainIsRefused() {
        assertThatThrownBy(() -> record(em, "", "DHOLM", "order", "25-1044"))
                .isInstanceOf(AuditError.class)
                .hasMessageContaining("needs an action");
        assertThatThrownBy(() -> record(em, "order.confirmed", "", "order", "25-1044"))
                .isInstanceOf(AuditError.class)
                .hasMessageContaining("was done by nobody");
        assertThatThrownBy(() -> record(em, "order.confirmed", "DHOLM", "", "25-1044"))
                .isInstanceOf(AuditError.class)
                .hasMessageContaining("about nothing in particular");
        assertThatThrownBy(() -> record(em, "order.confirmed", "DHOLM", "order", ""))
                .isInstanceOf(AuditError.class)
                .hasMessageContaining("about nothing in particular");
    }

    @Test
    void theReferenceIsWrittenAsTextWhateverItCameInAs() {
        record(em, "invoice.raised", "billing", "invoice", 1044, NINE);
        session.commit();
        assertThat(theOnlyRow().getReference()).isEqualTo("1044");
    }

    @Test
    void aDetailValueWithASpaceDoesNotSplitAField() {
        record(em, "order.written", "DHOLM", "order", "25-1044", NINE, field("advertiser", "Black Hawk Bank"));
        session.commit();
        assertThat(theOnlyRow().getDetail()).isEqualTo("advertiser=\"Black Hawk Bank\"");
    }

    @Test
    void nothingPointsAtAnythingSoAnEntryOutlivesItsDocument() throws SQLException {
        assertThat(foreignKeysOn("AUDIT")).isZero();
        // The same question asked of a table that does point somewhere, so a zero means something.
        assertThat(foreignKeysOn("CUT")).isPositive();
        record(em, "order.deleted", "DHOLM", "order", "25-9999", NINE);
        session.commit();
        // There has never been an order 25-9999 and the entry is still there.
        assertThat(count(em)).isEqualTo(1);
    }

    @Test
    void theTrailOfOneDocumentComesBackOldestFirst() {
        record(em, "order.written", "DHOLM", "order", "25-1044", TEN);
        record(em, "order.confirmed", "DHOLM", "order", "25-1044", NINE);
        record(em, "order.written", "DHOLM", "order", "25-2000", NINE);
        session.commit();
        assertThat(about(em, "order", "25-1044"))
                .extracting(Audit.Entry::action)
                .containsExactly("order.confirmed", "order.written");
        assertThat(about(em, "order", "25-9999")).isEmpty();
    }

    @Test
    void twoEntriesAtTheSameMomentComeBackInTheOrderWritten() {
        record(em, "first", "DHOLM", "order", "25-1044", NINE);
        record(em, "second", "DHOLM", "order", "25-1044", NINE);
        session.commit();
        assertThat(about(em, "order", "25-1044"))
                .extracting(Audit.Entry::action)
                .containsExactly("first", "second");
    }

    @Test
    void whatOnePersonDidInASpan() {
        record(em, "order.written", "DHOLM", "order", "25-1044", NINE);
        record(em, "order.written", "DHOLM", "order", "25-2000", LocalDateTime.of(2025, 8, 6, 9, 0));
        record(em, "order.written", "TRONAN", "order", "25-3000", NINE);
        session.commit();
        assertThat(byActor(em, "DHOLM", MONDAY, LocalDate.of(2025, 8, 10))).hasSize(2);
        assertThat(byActor(em, "DHOLM", MONDAY, MONDAY)).hasSize(1);
        assertThat(byActor(em, "NOBODY", MONDAY, LocalDate.of(2025, 8, 10))).isEmpty();
    }

    @Test
    void aSpanThatRunsBackwardsIsRefused() {
        assertThatThrownBy(() -> byActor(em, "DHOLM", LocalDate.of(2025, 8, 10), MONDAY))
                .isInstanceOf(AuditError.class)
                .hasMessageContaining("runs backwards");
    }

    @Test
    void everythingThatHappenedOnOneDay() {
        record(em, "a", "DHOLM", "order", "1", LocalDateTime.of(2025, 8, 4, 0, 0, 0));
        record(em, "b", "TRONAN", "log", "2", LocalDateTime.of(2025, 8, 4, 23, 59, 59));
        record(em, "c", "DHOLM", "order", "3", LocalDateTime.of(2025, 8, 5, 0, 0, 0));
        session.commit();
        assertThat(onDay(em, MONDAY)).extracting(Audit.Entry::action).containsExactly("a", "b");
        assertThat(onDay(em, LocalDate.of(2025, 8, 5)))
                .extracting(Audit.Entry::action)
                .containsExactly("c");
        assertThat(onDay(em, LocalDate.of(2025, 8, 6))).isEmpty();
    }

    @Test
    void anEntryReadsAsASentence() {
        record(em, "order.confirmed", "DHOLM", "order", "25-1044", NINE, field("spots", 48));
        session.commit();
        String printed = about(em, "order", "25-1044").get(0).toString();
        assertThat(printed).isEqualTo("2025-08-04T09:00:00 DHOLM order.confirmed order:25-1044 spots=48");
    }

    @Test
    void aFailedWriteDoesNotTakeTheCallersWorkWithIt() {
        // A bad entry raises through record and is swallowed by tryRecord.
        assertThatThrownBy(() -> record(em, "", "DHOLM", "order", "25-1044")).isInstanceOf(AuditError.class);
        assertThat(HouseLog.quietly(() -> tryRecord(em, "", "DHOLM", "order", "25-1044")))
                .isEmpty();
        record(em, "order.confirmed", "DHOLM", "order", "25-1044", NINE);
        session.commit();
        assertThat(count(em)).isEqualTo(1);
    }

    @Test
    void theGoodCaseOfTryRecordWritesTheRow() {
        assertThat(tryRecord(em, "order.written", "DHOLM", "order", "25-1044")).isPresent();
        session.commit();
        assertThat(count(em)).isEqualTo(1);
    }

    @Test
    void theUnattendedJobsHaveNamesOfTheirOwn() {
        record(em, "log.built", NIGHTLY, "log", "KWLO 2025-08-04", NINE);
        session.commit();
        assertThat(byActor(em, NIGHTLY, MONDAY, MONDAY).get(0).actor()).isEqualTo("log builder");
    }
}
