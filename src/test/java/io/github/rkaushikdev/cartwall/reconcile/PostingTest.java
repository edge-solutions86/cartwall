package io.github.rkaushikdev.cartwall.reconcile;

import static io.github.rkaushikdev.cartwall.Fixtures.MONDAY;
import static io.github.rkaushikdev.cartwall.Fixtures.aGrid;
import static io.github.rkaushikdev.cartwall.domain.SpotLength.THIRTY;
import static io.github.rkaushikdev.cartwall.reconcile.Matcher.reconcile;
import static io.github.rkaushikdev.cartwall.reconcile.Posting.airedSpots;
import static io.github.rkaushikdev.cartwall.reconcile.Posting.missedSpots;
import static io.github.rkaushikdev.cartwall.reconcile.Posting.post;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.github.rkaushikdev.cartwall.AirTime;
import io.github.rkaushikdev.cartwall.Duration;
import io.github.rkaushikdev.cartwall.Fixtures;
import io.github.rkaushikdev.cartwall.HouseLog;
import io.github.rkaushikdev.cartwall.domain.ClassOfTime;
import io.github.rkaushikdev.cartwall.domain.ClockGrid;
import io.github.rkaushikdev.cartwall.logs.DayLog;
import io.github.rkaushikdev.cartwall.persistence.AccountRepository;
import io.github.rkaushikdev.cartwall.persistence.LogRepository;
import io.github.rkaushikdev.cartwall.persistence.StationRepository;
import io.github.rkaushikdev.cartwall.persistence.TestDatabase;
import io.github.rkaushikdev.cartwall.persistence.UnitOfWork;
import io.github.rkaushikdev.cartwall.persistence.models.SpotRow;
import io.github.rkaushikdev.cartwall.playout.AsRun;
import io.github.rkaushikdev.cartwall.playout.AsRunFile;
import io.github.rkaushikdev.cartwall.playout.Played;
import io.github.rkaushikdev.cartwall.scheduling.DayBoard;
import io.github.rkaushikdev.cartwall.scheduling.Placement;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PostingTest {

    private static final ClassOfTime FIXED = ClassOfTime.FIXED;
    private static final String HEADER = "date,time,cart,title,length,status,reference";
    private static final LocalDateTime SIGNED = LocalDateTime.of(2025, 8, 1, 16, 30);
    private static final LocalDate TUESDAY = LocalDate.of(2025, 8, 5);

    private UnitOfWork session;
    private EntityManager em;
    private ClockGrid grid;

    @BeforeEach
    void openASessionOnTheClusterAndTheBook() {
        session = TestDatabase.fresh().unitOfWork();
        em = session.entityManager();
        new StationRepository(em).saveCluster(Fixtures.cluster());
        new AccountRepository(em).saveBook(Fixtures.accounts());
        session.commit();
        grid = aGrid("KWLO");
    }

    @AfterEach
    void closeTheSession() {
        session.close();
    }

    private static void put(DayBoard board, int hour, int number, String cart, int line) {
        board.avail(hour, number).add(new Placement("25-1044", line, "HYVEE", THIRTY, FIXED, cart));
    }

    private static void put(DayBoard board, int hour, int number) {
        put(board, hour, number, "C1204", 1);
    }

    private static List<AsRun> aFile(String... lines) {
        List<String> all = new ArrayList<>();
        all.add(HEADER);
        all.addAll(List.of(lines));
        return AsRunFile.parse(String.join("\n", all) + "\n");
    }

    private static String ran(String at, String cart, String status, String reference, String length) {
        return "2025-08-04," + at + "," + cart + ",a title," + length + "," + status + "," + reference;
    }

    private static String ran(String at) {
        return ran(at, "C1204", "played", "25-1044-1", "30.0");
    }

    private static String ran(String at, String status) {
        return ran(at, "C1204", status, "25-1044-1", "30.0");
    }

    /** Walk a day all the way through to aired, which is where posting starts. */
    private LogRepository airedDay(DayBoard board) {
        LogRepository logs = new LogRepository(em);
        logs.save(board);
        logs.approve("KWLO", MONDAY, "T. Ronan", SIGNED);
        logs.release("KWLO", MONDAY, SIGNED);
        logs.markAired("KWLO", MONDAY);
        return logs;
    }

    private static List<SpotRow> spotsOf(LogRepository logs) {
        return logs.row("KWLO", MONDAY).orElseThrow().getSpots();
    }

    private Reconciliation reconciled(DayBoard board, List<AsRun> rows) {
        return reconcile(DayLog.build(grid, board), rows);
    }

    @Test
    void aDayThatHasNotAiredCannotBePosted() {
        DayBoard board = DayBoard.build(grid, MONDAY);
        put(board, 7, 2);
        new LogRepository(em).save(board);
        Reconciliation result = reconciled(board, aFile(ran("07:10:00")));
        NotAired caught = catchThrowableOfType(NotAired.class, () -> post(em, result));
        assertThat(caught).hasMessageContaining("is draft, not aired");
        assertThat(caught.context()).containsEntry("state", "draft");
    }

    @Test
    void aDayThatWasNeverBuiltCannotBePosted() {
        DayBoard board = DayBoard.build(grid, MONDAY);
        Reconciliation result = reconciled(board, List.of());
        assertThatThrownBy(() -> post(em, result)).isInstanceOf(NotAired.class).hasMessageContaining("has no log for");
    }

    @Test
    void aDraftDayCanBePostedWhenSomebodyInsists() {
        DayBoard board = DayBoard.build(grid, MONDAY);
        put(board, 7, 2);
        new LogRepository(em).save(board);
        Reconciliation result = reconciled(board, aFile(ran("07:10:00")));
        assertThat(post(em, result, true).aired()).isEqualTo(1);
    }

    @Test
    void aSpotThatAiredIsWrittenBackWithWhenAndHowLong() {
        DayBoard board = DayBoard.build(grid, MONDAY);
        put(board, 7, 2);
        LogRepository logs = airedDay(board);
        Reconciliation result = reconciled(board, aFile(ran("07:10:04", "C1204", "played", "25-1044-1", "29.7")));
        Posting report = post(em, result);
        assertThat(report.aired()).isEqualTo(1);
        assertThat(report.missed()).isZero();

        SpotRow spot = spotsOf(logs).get(0);
        assertThat(spot.isAired()).isTrue();
        assertThat(spot.getAiredAt()).isEqualTo(AirTime.at(7, 10, 4));
        assertThat(spot.getAiredLength()).isEqualTo(new Duration(297));
        assertThat(spot.getAiredStatus()).isSameAs(Played.PLAYED);
    }

    @Test
    void aSpotThatWasSkippedIsWrittenBackAsNotAired() {
        DayBoard board = DayBoard.build(grid, MONDAY);
        put(board, 7, 2);
        LogRepository logs = airedDay(board);
        Reconciliation result = reconciled(board, aFile(ran("07:10:00", "skip")));
        Posting report = HouseLog.quietly(() -> post(em, result));
        assertThat(report.aired()).isZero();
        assertThat(report.missed()).isEqualTo(1);
        SpotRow spot = spotsOf(logs).get(0);
        assertThat(spot.isAired()).isFalse();
        assertThat(spot.getAiredStatus()).isSameAs(Played.SKIPPED);
        assertThat(spot.getAiredAt()).isNull();
    }

    @Test
    void aSpotWithNoAsRunLineIsLeftUnknown() {
        DayBoard board = DayBoard.build(grid, MONDAY);
        put(board, 7, 2);
        LogRepository logs = airedDay(board);
        HouseLog.quietly(() -> post(em, reconciled(board, List.of())));
        SpotRow spot = spotsOf(logs).get(0);
        assertThat(spot.isAired()).isFalse();
        assertThat(spot.getAiredStatus()).isSameAs(Played.UNKNOWN);
    }

    @Test
    void aMovedSpotIsCountedAndStillAired() {
        DayBoard board = DayBoard.build(grid, MONDAY);
        put(board, 7, 2);
        LogRepository logs = airedDay(board);
        Posting report = post(em, reconciled(board, aFile(ran("08:22:00"))));
        assertThat(report.aired()).isEqualTo(1);
        assertThat(report.moved()).isEqualTo(1);
        assertThat(spotsOf(logs).get(0).isAired()).isTrue();
    }

    @Test
    void anUnloggedAsRunLineIsCountedAndWrittenNowhere() {
        DayBoard board = DayBoard.build(grid, MONDAY);
        put(board, 7, 2);
        airedDay(board);
        List<AsRun> rows = aFile(ran("07:10:00"), ran("15:10:00", "C9999", "played", "25-9999-1", "30.0"));
        Posting report = post(em, reconciled(board, rows));
        assertThat(report.unmatched()).isEqualTo(1);
        assertThat(report.aired()).isEqualTo(1);
    }

    @Test
    void postingTwiceGivesTheSameAnswer() {
        DayBoard board = DayBoard.build(grid, MONDAY);
        put(board, 7, 2, "C1204", 1);
        put(board, 9, 4, "C1205", 2);
        LogRepository logs = airedDay(board);
        List<AsRun> rows = aFile(ran("07:10:00", "C1204", "played", "25-1044-1", "30.0"));
        Posting first = HouseLog.quietly(() -> post(em, reconciled(board, rows)));
        Posting second = HouseLog.quietly(() -> post(em, reconciled(board, rows)));
        assertThat(List.of(first.aired(), first.missed())).isEqualTo(List.of(second.aired(), second.missed()));
        assertThat(spotsOf(logs)).extracting(SpotRow::isAired).containsExactly(true, false);
    }

    @Test
    void aCorrectedAsRunPostedLaterWins() {
        DayBoard board = DayBoard.build(grid, MONDAY);
        put(board, 7, 2);
        LogRepository logs = airedDay(board);
        List<AsRun> skipped = aFile(ran("07:10:00", "skip"));
        HouseLog.quietly(() -> post(em, reconciled(board, skipped)));
        assertThat(spotsOf(logs).get(0).isAired()).isFalse();
        post(em, reconciled(board, aFile(ran("07:10:00"))));
        assertThat(spotsOf(logs).get(0).isAired()).isTrue();
    }

    @Test
    void theReportReadsAsASentence() {
        DayBoard board = DayBoard.build(grid, MONDAY);
        put(board, 7, 2, "C1204", 1);
        put(board, 9, 4, "C1205", 2);
        airedDay(board);
        List<AsRun> rows = aFile(ran("07:10:00", "C1204", "played", "25-1044-1", "30.0"));
        Posting report = HouseLog.quietly(() -> post(em, reconciled(board, rows)));
        assertThat(report).hasToString("KWLO 2025-08-04: 1 aired, 1 missed, 0 moved, 0 not on the log");
    }

    @Test
    void billingReadsOnlyWhatAired() {
        DayBoard board = DayBoard.build(grid, MONDAY);
        put(board, 7, 2, "C1204", 1);
        put(board, 9, 4, "C1205", 2);
        put(board, 15, 2, "C1206", 3);
        airedDay(board);
        List<AsRun> rows = aFile(
                ran("07:10:00", "C1204", "played", "25-1044-1", "30.0"),
                ran("09:38:00", "C1205", "played", "25-1044-2", "30.0"),
                ran("15:10:00", "C1206", "error", "25-1044-3", "30.0"));
        HouseLog.quietly(() -> post(em, reconciled(board, rows)));
        session.commit();

        List<SpotRow> billable = airedSpots(em, "25-1044", MONDAY, TUESDAY);
        assertThat(billable).extracting(SpotRow::getLine).containsExactly(1, 2);
        List<SpotRow> owed = missedSpots(em, "25-1044", MONDAY, TUESDAY);
        assertThat(owed).extracting(SpotRow::getLine).containsExactly(3);
    }

    @Test
    void aDayThatHasNotAiredIsNotOwedAnythingYet() {
        DayBoard board = DayBoard.build(grid, MONDAY);
        put(board, 7, 2);
        new LogRepository(em).save(board);
        session.commit();
        assertThat(missedSpots(em, "25-1044", MONDAY, TUESDAY)).isEmpty();
        assertThat(airedSpots(em, "25-1044", MONDAY, TUESDAY)).isEmpty();
    }
}
