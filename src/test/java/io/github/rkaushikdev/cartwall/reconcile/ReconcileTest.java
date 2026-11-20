package io.github.rkaushikdev.cartwall.reconcile;

import static io.github.rkaushikdev.cartwall.Fixtures.MONDAY;
import static io.github.rkaushikdev.cartwall.domain.SpotLength.SIXTY;
import static io.github.rkaushikdev.cartwall.domain.SpotLength.THIRTY;
import static io.github.rkaushikdev.cartwall.reconcile.Matcher.missedByStatus;
import static io.github.rkaushikdev.cartwall.reconcile.Matcher.reconcile;
import static io.github.rkaushikdev.cartwall.reconcile.Matcher.worstDrift;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.github.rkaushikdev.cartwall.Duration;
import io.github.rkaushikdev.cartwall.Fixtures;
import io.github.rkaushikdev.cartwall.domain.ClassOfTime;
import io.github.rkaushikdev.cartwall.domain.ClockGrid;
import io.github.rkaushikdev.cartwall.domain.SpotLength;
import io.github.rkaushikdev.cartwall.logs.DayLog;
import io.github.rkaushikdev.cartwall.playout.AsRun;
import io.github.rkaushikdev.cartwall.playout.AsRunFile;
import io.github.rkaushikdev.cartwall.scheduling.DayBoard;
import io.github.rkaushikdev.cartwall.scheduling.LineKey;
import io.github.rkaushikdev.cartwall.scheduling.Placement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ReconcileTest {

    private static final String HEADER = "date,time,cart,title,length,status,reference";

    private ClockGrid grid;
    private DayBoard board;

    @BeforeEach
    void layOutMonday() {
        grid = Fixtures.aGrid("KWLO");
        board = DayBoard.build(grid, MONDAY);
    }

    private void put(int hour, int number, String cart, int line, SpotLength length) {
        board.avail(hour, number).add(new Placement("25-1044", line, "HYVEE", length, ClassOfTime.FIXED, cart, false));
    }

    private void put(int hour, int number, String cart, int line) {
        put(hour, number, cart, line, THIRTY);
    }

    private DayLog log() {
        return DayLog.build(grid, board);
    }

    static List<AsRun> aFile(String... lines) {
        List<String> all = new ArrayList<>();
        all.add(HEADER);
        all.addAll(List.of(lines));
        return AsRunFile.parse(String.join("\n", all) + "\n");
    }

    static String ran(String at, String cart, String length, String status, String reference, String day) {
        return day + "," + at + "," + cart + ",a title," + length + "," + status + "," + reference;
    }

    static String ran(String at, String cart, String status, String reference) {
        return ran(at, cart, "30.0", status, reference, "2025-08-04");
    }

    static String ran(String at, String reference) {
        return ran(at, "C1204", "played", reference);
    }

    static String ran(String at) {
        return ran(at, "25-1044-1");
    }

    private static Map<Integer, Match> byLine(Reconciliation result) {
        return result.matched().stream()
                .collect(Collectors.toMap(match -> match.scheduled().line(), Function.identity()));
    }

    @Test
    void aCleanDayMatchesEverythingByReference() {
        put(7, 2, "C1204", 1);
        put(9, 4, "C1205", 2);
        Reconciliation result =
                reconcile(log(), aFile(ran("07:10:00", "25-1044-1"), ran("09:38:00", "C1205", "played", "25-1044-2")));
        assertThat(result.scheduled()).isEqualTo(2);
        assertThat(result.aired()).isEqualTo(2);
        assertThat(result.missed()).isEmpty();
        assertThat(result.extra()).isEmpty();
        assertThat(result.isClean()).isTrue();
        assertThat(result.matched()).allMatch(Match::byReference);
    }

    @Test
    void aFewSecondsOfDriftIsStillOnTime() {
        put(7, 2, "C1204", 1);
        Reconciliation result = reconcile(log(), aFile(ran("07:10:04")));
        Match match = result.matched().get(0);
        assertThat(match.drift()).isEqualTo(Duration.ofSeconds(4));
        assertThat(match.onTime()).isTrue();
        assertThat(match.moved()).isFalse();
        assertThat(result.isClean()).isTrue();
        assertThat(match).hasToString("25-1044-1 on time");
    }

    @Test
    void aSpotThatRanAnHourLateIsMovedAndStillBillable() {
        put(7, 2, "C1204", 1);
        Reconciliation result = reconcile(log(), aFile(ran("08:10:00")));
        Match match = result.matched().get(0);
        assertThat(match.moved()).isTrue();
        assertThat(match.billable()).isTrue();
        assertThat(match.drift()).isEqualTo(Duration.ofSeconds(3600));
        assertThat(result.isClean()).isFalse();
        assertThat(result.moved()).containsExactly(match);
        assertThat(match.toString()).contains("moved 1:00:00");
    }

    @Test
    void theLineBetweenOnTimeAndMovedIsFiveMinutes() {
        put(7, 2, "C1204", 1);
        DayLog log = log();
        assertThat(reconcile(log, aFile(ran("07:15:00"))).matched().get(0).onTime())
                .isTrue();
        assertThat(reconcile(log, aFile(ran("07:15:01"))).matched().get(0).moved())
                .isTrue();
        assertThat(Match.ON_TIME).isEqualTo(Duration.ofSeconds(300));
    }

    @Test
    void aSpotTheAutomationSkippedIsMissedWithItsOwnReason() {
        put(7, 2, "C1204", 1);
        Reconciliation result = reconcile(log(), aFile(ran("07:10:00", "C1204", "skip", "25-1044-1")));
        assertThat(result.matched()).isEmpty();
        assertThat(result.missed()).hasSize(1);
        assertThat(result.missed().get(0).reason()).isEqualTo("skipped");
        assertThat(result.missed().get(0).toString()).contains("25-1044-1 missed: skipped");
        assertThat(result.aired()).isZero();
    }

    @Test
    void aSpotWithNoAsRunLineAtAllIsMissed() {
        put(7, 2, "C1204", 1);
        put(9, 4, "C1205", 2);
        Reconciliation result = reconcile(log(), aFile(ran("07:10:00", "25-1044-1")));
        assertThat(result.missed()).extracting(Missed::key).containsExactly(new LineKey("25-1044", 2));
        assertThat(result.missed().get(0).reason()).isEqualTo("no as-run line");
    }

    @Test
    void anAsRunLineNobodyLoggedIsExtra() {
        put(7, 2, "C1204", 1);
        Reconciliation result =
                reconcile(log(), aFile(ran("07:10:00"), ran("15:22:00", "C9999", "played", "25-9999-1")));
        assertThat(result.extra()).hasSize(1);
        assertThat(result.extra().get(0).aired().cart()).isEqualTo("C9999");
        assertThat(result.extra().get(0).toString()).contains("was not on the log");
    }

    @Test
    void aLineWithNoReferenceMatchesOnCartAndTime() {
        put(7, 2, "C1204", 1);
        Reconciliation result = reconcile(log(), aFile(ran("07:10:03", "")));
        assertThat(result.matched()).hasSize(1);
        assertThat(result.matched().get(0).byReference()).isFalse();
        assertThat(result.matched().get(0).onTime()).isTrue();
    }

    @Test
    void twoRunsOfOneCartMatchTheNearerScheduledSpotEach() {
        put(7, 2, "C1204", 1);
        put(15, 2, "C1204", 2);
        Reconciliation result = reconcile(log(), aFile(ran("15:10:05", ""), ran("07:10:05", "")));
        assertThat(result.matched()).hasSize(2);
        Map<Integer, Match> byLine = byLine(result);
        assertThat(byLine.get(1).aired().at().hour()).isEqualTo(7);
        assertThat(byLine.get(2).aired().at().hour()).isEqualTo(15);
        assertThat(result.matched()).allMatch(Match::onTime);
    }

    @Test
    void aCartMatchOutsideTheWindowIsNotAMatch() {
        put(7, 2, "C1204", 1);
        Reconciliation result = reconcile(log(), aFile(ran("15:10:00", "")));
        assertThat(result.matched()).isEmpty();
        assertThat(result.missed()).hasSize(1);
        assertThat(result.extra()).hasSize(1);
    }

    @Test
    void theWindowCanBeWidenedWhenADayRanBadlyLate() {
        put(7, 2, "C1204", 1);
        List<AsRun> rows = aFile(ran("09:10:00", ""));
        Reconciliation tight = reconcile(log(), rows);
        Reconciliation wide = reconcile(log(), rows, Duration.ofSeconds(3 * 3600));
        assertThat(tight.matched()).isEmpty();
        assertThat(wide.matched()).hasSize(1);
        assertThat(wide.matched().get(0).moved()).isTrue();
    }

    @Test
    void theReferencePassWinsOverTheCartPass() {
        put(7, 2, "C1204", 1);
        put(7, 3, "C1204", 2);
        Reconciliation result = reconcile(log(), aFile(ran("07:22:05", "25-1044-1"), ran("07:10:05", "")));
        Map<Integer, Match> byLine = byLine(result);
        assertThat(byLine.get(1).byReference()).isTrue();
        assertThat(byLine.get(1).aired().at().hour()).isEqualTo(7);
        assertThat(byLine.get(1).aired().at().minute()).isEqualTo(22);
        assertThat(byLine.get(2).byReference()).isFalse();
    }

    @Test
    void aSpotThatRanShortIsFlaggedWithoutBeingMissed() {
        put(7, 2, "C1204", 1, SIXTY);
        Reconciliation result =
                reconcile(log(), aFile(ran("07:10:00", "C1204", "19.0", "trunc", "25-1044-1", "2025-08-04")));
        Match match = result.matched().get(0);
        assertThat(match.isShort()).isTrue();
        assertThat(match.billable()).isTrue();
        assertThat(match.aired().status().aired()).isTrue();
    }

    @Test
    void anAsRunForAnotherDayIsRefused() {
        put(7, 2, "C1204", 1);
        List<AsRun> rows = aFile(ran("07:10:00", "C1204", "30.0", "played", "25-1044-1", "2025-08-05"));
        ReconcileError caught = catchThrowableOfType(ReconcileError.class, () -> reconcile(log(), rows));
        assertThat(caught).hasMessageContaining("line(s) for another day");
        assertThat(caught.context()).containsEntry("log_day", "2025-08-04");
    }

    @Test
    void anEmptyDayReconcilesToNothing() {
        Reconciliation result = reconcile(log(), List.of());
        assertThat(result.scheduled()).isZero();
        assertThat(result.isClean()).isTrue();
        assertThat(worstDrift(result)).isEqualTo(new Duration(0));
    }

    @Test
    void theResultReadsAsASentence() {
        put(7, 2, "C1204", 1);
        put(9, 4, "C1205", 2);
        Reconciliation result = reconcile(log(), aFile(ran("07:10:00", "25-1044-1")));
        assertThat(result).hasToString("KWLO 2025-08-04: 1 of 2 aired, 1 missed, 0 unlogged");
    }

    @Test
    void whatALineIsOwedComesBackWorstFirst() {
        for (int[] spot : new int[][] {{7, 1}, {9, 1}, {11, 2}, {13, 1}}) {
            put(spot[0], 2, "C120" + spot[1], spot[1]);
        }
        Map<LineKey, Integer> owed = reconcile(log(), List.of()).owed();
        assertThat(owed).isEqualTo(Map.of(new LineKey("25-1044", 1), 3, new LineKey("25-1044", 2), 1));
        assertThat(owed.keySet()).containsExactly(new LineKey("25-1044", 1), new LineKey("25-1044", 2));
    }

    @Test
    void theMissedAreCountedByTheReasonTheAutomationGave() {
        put(7, 2, "C1201", 1);
        put(9, 2, "C1202", 2);
        put(11, 2, "C1203", 3);
        Reconciliation result = reconcile(
                log(),
                aFile(ran("07:10:00", "C1201", "skip", "25-1044-1"), ran("09:10:00", "C1202", "error", "25-1044-2")));
        assertThat(missedByStatus(result)).isEqualTo(Map.of("error", 1, "no as-run line", 1, "skipped", 1));
    }

    @Test
    void theWorstDriftOfADayIsReportedForTheMorningMeeting() {
        put(7, 2, "C1201", 1);
        put(9, 2, "C1202", 2);
        Reconciliation result = reconcile(
                log(),
                aFile(
                        ran("07:10:04", "C1201", "played", "25-1044-1"),
                        ran("09:22:00", "C1202", "played", "25-1044-2")));
        assertThat(worstDrift(result)).isEqualTo(Duration.ofSeconds(12 * 60));
    }

    @Test
    void everythingComesBackInAirOrder() {
        for (int[] spot : new int[][] {{15, 3}, {7, 1}, {9, 2}}) {
            put(spot[0], 2, "C120" + spot[1], spot[1]);
        }
        Reconciliation result = reconcile(
                log(),
                aFile(
                        ran("15:10:00", "C1203", "played", "25-1044-3"),
                        ran("07:10:00", "C1201", "played", "25-1044-1")));
        assertThat(result.matched())
                .extracting(match -> match.aired().at().hour())
                .containsExactly(7, 15);
        assertThat(result.missed())
                .extracting(miss -> miss.scheduled().at().hour())
                .containsExactly(9);
    }

    @Test
    void theResultFindsEveryRunOfOneLine() {
        put(7, 2, "C1204", 1);
        put(9, 2, "C1204", 1);
        Reconciliation result = reconcile(log(), aFile(ran("07:10:00", "25-1044-1"), ran("09:10:00", "25-1044-1")));
        assertThat(result.forLine("25-1044", 1)).hasSize(2);
        assertThat(result.forLine("25-1044", 9)).isEmpty();
    }
}
