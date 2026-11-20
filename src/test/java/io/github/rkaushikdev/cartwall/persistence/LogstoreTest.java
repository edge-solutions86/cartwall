package io.github.rkaushikdev.cartwall.persistence;

import static io.github.rkaushikdev.cartwall.Fixtures.MONDAY;
import static io.github.rkaushikdev.cartwall.Fixtures.aGrid;
import static io.github.rkaushikdev.cartwall.domain.SpotLength.SIXTY;
import static io.github.rkaushikdev.cartwall.domain.SpotLength.THIRTY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.assertj.core.api.Assertions.tuple;

import io.github.rkaushikdev.cartwall.AirTime;
import io.github.rkaushikdev.cartwall.Fixtures;
import io.github.rkaushikdev.cartwall.LookupFailure;
import io.github.rkaushikdev.cartwall.domain.ClassOfTime;
import io.github.rkaushikdev.cartwall.domain.ClockGrid;
import io.github.rkaushikdev.cartwall.domain.SpotLength;
import io.github.rkaushikdev.cartwall.logs.LogState;
import io.github.rkaushikdev.cartwall.logs.LogStatus;
import io.github.rkaushikdev.cartwall.persistence.models.BumpRow;
import io.github.rkaushikdev.cartwall.persistence.models.SpotRow;
import io.github.rkaushikdev.cartwall.scheduling.BumpQueue;
import io.github.rkaushikdev.cartwall.scheduling.Bumped;
import io.github.rkaushikdev.cartwall.scheduling.DayBoard;
import io.github.rkaushikdev.cartwall.scheduling.LineKey;
import io.github.rkaushikdev.cartwall.scheduling.Placement;
import io.github.rkaushikdev.cartwall.scheduling.Timed;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LogstoreTest {

    private static final ClassOfTime FIXED = ClassOfTime.FIXED;
    private static final ClassOfTime IMMEDIATE = ClassOfTime.IMMEDIATE;
    private static final LocalDateTime SIGNED = LocalDateTime.of(2025, 8, 1, 16, 30);
    private static final LocalDateTime SENT = LocalDateTime.of(2025, 8, 1, 17, 5);
    private static final LocalDate TUESDAY = LocalDate.of(2025, 8, 5);

    private UnitOfWork session;
    private EntityManager em;
    private ClockGrid grid;
    private DayBoard board;

    @BeforeEach
    void openASessionOnTheClusterAndTheBook() {
        session = TestDatabase.fresh().unitOfWork();
        em = session.entityManager();
        new StationRepository(em).saveCluster(Fixtures.cluster());
        new AccountRepository(em).saveBook(Fixtures.accounts());
        session.commit();
        grid = aGrid("KWLO");
        board = DayBoard.build(grid, MONDAY);
    }

    @AfterEach
    void closeTheSession() {
        session.close();
    }

    private static void put(
            DayBoard board, int hour, int number, String order, int line, SpotLength length, boolean fixed) {
        board.avail(hour, number).add(new Placement(order, line, "HYVEE", length, FIXED, "C1204", fixed));
    }

    private static void put(DayBoard board, int hour, int number, int line, SpotLength length) {
        put(board, hour, number, "25-1044", line, length, false);
    }

    private static void put(DayBoard board, int hour, int number, int line) {
        put(board, hour, number, line, THIRTY);
    }

    private static Bumped aBump(LocalDate day, int line) {
        return new Bumped(new Placement("25-0100", line, "SLUMB", THIRTY, IMMEDIATE), day, 7, 2, "25-9000", 1);
    }

    private static Bumped aBump() {
        return aBump(MONDAY, 3);
    }

    private static List<Integer> lines(List<Timed> placements) {
        return placements.stream().map(timed -> timed.placement().line()).toList();
    }

    private static <T> List<T> reversed(List<T> items) {
        List<T> copy = new ArrayList<>(items);
        Collections.reverse(copy);
        return copy;
    }

    @Test
    void aBoardTurnsIntoRowsInAirOrder() {
        put(board, 9, 4, 3);
        put(board, 7, 2, 1);
        put(board, 7, 2, 2, SIXTY);
        List<SpotRow> rows = LogStore.spotRows(board);
        assertThat(rows).extracting(SpotRow::getLine).containsExactly(1, 2, 3);
        assertThat(rows).extracting(SpotRow::getPosition).containsExactly(0, 1, 0);
        assertThat(rows)
                .extracting(SpotRow::getAt)
                .containsExactly(AirTime.at(7, 10), AirTime.at(7, 10, 30), AirTime.at(9, 38));
    }

    @Test
    void rowsComeBackAsABoardWithTheSameSpots() {
        put(board, 7, 2, 1);
        put(board, 7, 2, 2, SIXTY);
        put(board, 15, 3, 3);
        DayBoard back = LogStore.boardFrom(grid, MONDAY, LogStore.spotRows(board));
        assertThat(back.usedUnits()).isEqualTo(board.usedUnits());
        assertThat(back.avail(7, 2).usedUnits()).isEqualTo(3);
        assertThat(lines(back.placements())).containsExactly(1, 2, 3);
    }

    @Test
    void rowsOutOfOrderStillRebuildTheBreakInOrder() {
        put(board, 7, 2, 1, SIXTY);
        put(board, 7, 2, 2);
        DayBoard back = LogStore.boardFrom(grid, MONDAY, reversed(LogStore.spotRows(board)));
        assertThat(back.avail(7, 2).placements()).extracting(Placement::line).containsExactly(1, 2);
    }

    @Test
    void aPinnedSpotStaysPinnedThroughTheRoundTrip() {
        put(board, 7, 2, "25-1044", 1, THIRTY, true);
        DayBoard back = LogStore.boardFrom(grid, MONDAY, LogStore.spotRows(board));
        assertThat(back.avail(7, 2).placements().get(0).fixed()).isTrue();
    }

    @Test
    void aBoardGoesInAndComesBack() {
        put(board, 7, 2, 1);
        put(board, 9, 4, 2);
        LogRepository repo = new LogRepository(em);
        repo.save(board);
        session.commit();
        DayBoard back = repo.board(grid, MONDAY);
        assertThat(back.usedUnits()).isEqualTo(2);
        assertThat(lines(back.placements())).containsExactly(1, 2);
    }

    @Test
    void aSavedLogStartsInDraft() {
        LogRepository repo = new LogRepository(em);
        LogStatus status = repo.save(board);
        assertThat(status.state()).isSameAs(LogState.DRAFT);
        assertThat(status.revision()).isZero();
        assertThat(repo.exists("KWLO", MONDAY)).isTrue();
        assertThat(repo.exists("KWLO", TUESDAY)).isFalse();
    }

    @Test
    void savingAgainWritesOverTheDraft() {
        LogRepository repo = new LogRepository(em);
        put(board, 7, 2, 1);
        repo.save(board);
        session.commit();
        put(board, 7, 3, 2);
        repo.save(board);
        session.commit();
        assertThat(repo.board(grid, MONDAY).usedUnits()).isEqualTo(2);
    }

    @Test
    void aReleasedLogIsNotWrittenOver() {
        LogRepository repo = new LogRepository(em);
        repo.save(board);
        repo.approve("KWLO", MONDAY, "T. Ronan", SIGNED);
        repo.release("KWLO", MONDAY, SENT);
        session.commit();
        LogAlreadyOut caught = catchThrowableOfType(LogAlreadyOut.class, () -> repo.save(board));
        assertThat(caught).hasMessageContaining("revise it before writing over it");
        assertThat(caught.context()).containsEntry("state", "released");
    }

    @Test
    void aReleasedLogCanBeWrittenOverWhenSomebodyInsists() {
        LogRepository repo = new LogRepository(em);
        repo.save(board);
        repo.approve("KWLO", MONDAY, "T. Ronan", SIGNED);
        repo.release("KWLO", MONDAY, SENT);
        put(board, 7, 2, 1);
        assertThat(repo.save(board, true).state()).isSameAs(LogState.RELEASED);
    }

    @Test
    void revisingOpensItBackUpAndClearsTheSignature() {
        LogRepository repo = new LogRepository(em);
        repo.save(board);
        repo.approve("KWLO", MONDAY, "T. Ronan", SIGNED);
        repo.release("KWLO", MONDAY, SENT);
        LogStatus status = repo.revise("KWLO", MONDAY);
        assertThat(status.state()).isSameAs(LogState.DRAFT);
        assertThat(status.revision()).isEqualTo(1);
        assertThat(status.approvedBy()).isEmpty();
        assertThat(status.releasedAt()).isEmpty();
        repo.save(board);
    }

    @Test
    void theSignatureAndTheMomentSurviveACommit() {
        LogRepository repo = new LogRepository(em);
        repo.save(board);
        repo.approve("KWLO", MONDAY, "T. Ronan", SIGNED);
        session.commit();
        em.clear();
        LogStatus status = repo.status("KWLO", MONDAY);
        assertThat(status.approvedBy()).isEqualTo("T. Ronan");
        assertThat(status.approvedAt()).contains(SIGNED);
    }

    @Test
    void askingForADayThatWasNeverBuilt() {
        LogRepository repo = new LogRepository(em);
        assertThatThrownBy(() -> repo.status("KWLO", TUESDAY))
                .isInstanceOf(LookupFailure.class)
                .hasMessageContaining("has no log for 2025-08-05");
        assertThatThrownBy(() -> repo.board(grid, TUESDAY))
                .isInstanceOf(LookupFailure.class)
                .hasMessageContaining("has no log for");
        assertThatThrownBy(() -> repo.approve("KWLO", TUESDAY, "T. Ronan", SIGNED))
                .isInstanceOf(LookupFailure.class)
                .hasMessageContaining("has no log for");
    }

    @Test
    void aSpanOfDaysComesBackInDateOrder() {
        LogRepository repo = new LogRepository(em);
        for (LocalDate day : List.of(TUESDAY, MONDAY, LocalDate.of(2025, 8, 6))) {
            repo.save(DayBoard.build(grid, day));
        }
        session.commit();
        List<LogStatus> span = repo.between("KWLO", MONDAY, LocalDate.of(2025, 8, 6));
        assertThat(span).extracting(LogStatus::day).containsExactly(MONDAY, TUESDAY, LocalDate.of(2025, 8, 6));
        assertThat(repo.between("KWLO", LocalDate.of(2025, 9, 1), LocalDate.of(2025, 9, 30)))
                .isEmpty();
    }

    @Test
    void everySpotOfOneOrderAcrossASpanComesBackInAirOrder() {
        LogRepository repo = new LogRepository(em);
        List<Map.Entry<LocalDate, Integer>> plan =
                List.of(Map.entry(MONDAY, 9), Map.entry(MONDAY, 7), Map.entry(TUESDAY, 8));
        for (Map.Entry<LocalDate, Integer> entry : plan) {
            LocalDate day = entry.getKey();
            DayBoard onDay = repo.exists("KWLO", day) ? repo.board(grid, day) : DayBoard.build(grid, day);
            put(onDay, entry.getValue(), 2, "25-1044", 1, THIRTY, false);
            repo.save(onDay);
        }
        DayBoard putOther = DayBoard.build(grid, LocalDate.of(2025, 8, 6));
        put(putOther, 7, 2, "25-2000", 1, THIRTY, false);
        repo.save(putOther);
        session.commit();

        List<SpotRow> found = repo.spotsFor("25-1044", MONDAY, LocalDate.of(2025, 8, 10));
        assertThat(found)
                .extracting(row -> row.getLog().getDay(), SpotRow::getHour)
                .containsExactly(tuple(MONDAY, 7), tuple(MONDAY, 9), tuple(TUESDAY, 8));
        assertThat(repo.spotsFor("25-9999", MONDAY, TUESDAY)).isEmpty();
    }

    @Test
    void aBumpIsRecordedAgainstTheStationAndTheDay() {
        BumpRepository repo = new BumpRepository(em);
        BumpRow row = repo.record("KWLO", aBump());
        session.commit();
        assertThat(row.getStation()).isEqualTo("KWLO");
        assertThat(row.key()).isEqualTo(new LineKey("25-0100", 3));
        assertThat(row.isMadeGood()).isFalse();
    }

    @Test
    void aWholeQueueIsRecordedAtOnce() {
        BumpQueue queue = new BumpQueue();
        queue.add(aBump(MONDAY, 1));
        queue.add(aBump(MONDAY, 1));
        queue.add(aBump(TUESDAY, 2));
        BumpRepository repo = new BumpRepository(em);
        assertThat(repo.recordQueue("KWLO", queue)).hasSize(3);
        session.commit();
        assertThat(repo.outstanding()).hasSize(3);
        assertThat(repo.owed()).isEqualTo(Map.of(new LineKey("25-0100", 1), 2, new LineKey("25-0100", 2), 1));
    }

    @Test
    void outstandingBumpsComeBackOldestFirst() {
        BumpRepository repo = new BumpRepository(em);
        repo.record("KWLO", aBump(TUESDAY, 2));
        repo.record("KWLO", aBump(MONDAY, 1));
        session.commit();
        assertThat(repo.outstanding()).extracting(BumpRow::getDay).containsExactly(MONDAY, TUESDAY);
    }

    @Test
    void settlingABumpTakesItOffTheOutstandingList() {
        BumpRepository repo = new BumpRepository(em);
        BumpRow row = repo.record("KWLO", aBump());
        session.commit();
        repo.settle(row.getId());
        session.commit();
        assertThat(repo.outstanding()).isEmpty();
        assertThat(repo.owed()).isEmpty();
    }

    @Test
    void settlingABumpThatIsNotOnFile() {
        assertThatThrownBy(() -> new BumpRepository(em).settle(99))
                .isInstanceOf(LookupFailure.class)
                .hasMessageContaining("bump 99 is not on file");
    }

    @Test
    void theOutstandingListCanBeNarrowedToOneOrder() {
        BumpRepository repo = new BumpRepository(em);
        repo.record("KWLO", aBump(MONDAY, 1));
        Bumped other = aBump(MONDAY, 1);
        em.persist(repo.record("KWLO", other));
        session.commit();
        assertThat(repo.outstanding("25-0100")).hasSize(2);
        assertThat(repo.outstanding("25-9999")).isEmpty();
    }

    @Test
    void rebuildingADayClearsTheBumpsItHad() {
        BumpRepository repo = new BumpRepository(em);
        repo.record("KWLO", aBump(MONDAY, 3));
        repo.record("KWLO", aBump(TUESDAY, 3));
        session.commit();
        assertThat(repo.clearDay("KWLO", MONDAY)).isEqualTo(1);
        session.commit();
        assertThat(repo.outstanding()).extracting(BumpRow::getDay).containsExactly(TUESDAY);
    }
}
