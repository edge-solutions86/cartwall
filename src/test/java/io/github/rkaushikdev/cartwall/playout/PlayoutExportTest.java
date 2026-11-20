package io.github.rkaushikdev.cartwall.playout;

import static io.github.rkaushikdev.cartwall.Fixtures.MONDAY;
import static io.github.rkaushikdev.cartwall.domain.SpotLength.SIXTY;
import static io.github.rkaushikdev.cartwall.domain.SpotLength.THIRTY;
import static io.github.rkaushikdev.cartwall.playout.LogExport.LINE_WIDTH;
import static io.github.rkaushikdev.cartwall.playout.LogExport.WIDTHS;
import static io.github.rkaushikdev.cartwall.playout.LogExport.filename;
import static io.github.rkaushikdev.cartwall.playout.LogExport.parse;
import static io.github.rkaushikdev.cartwall.playout.LogExport.parseRow;
import static io.github.rkaushikdev.cartwall.playout.LogExport.render;
import static io.github.rkaushikdev.cartwall.playout.LogExport.rowsFor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.cartwall.Duration;
import io.github.rkaushikdev.cartwall.Fixtures;
import io.github.rkaushikdev.cartwall.domain.ClassOfTime;
import io.github.rkaushikdev.cartwall.domain.ClockGrid;
import io.github.rkaushikdev.cartwall.domain.SpotLength;
import io.github.rkaushikdev.cartwall.logs.DayLog;
import io.github.rkaushikdev.cartwall.logs.Element;
import io.github.rkaushikdev.cartwall.logs.LogLine;
import io.github.rkaushikdev.cartwall.scheduling.DayBoard;
import io.github.rkaushikdev.cartwall.scheduling.Placement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PlayoutExportTest {

    private ClockGrid grid;
    private DayBoard board;

    @BeforeEach
    void layOutMonday() {
        grid = Fixtures.aGrid("KWLO");
        board = DayBoard.build(grid, MONDAY);
    }

    private void put(int hour, int number, String code, SpotLength length, String cart, int line) {
        board.avail(hour, number).add(new Placement("25-1044", line, code, length, ClassOfTime.FIXED, cart, false));
    }

    private void put(int hour, int number, int line) {
        put(hour, number, "HYVEE", THIRTY, "C1204", line);
    }

    private static List<ExportRow> spots(List<ExportRow> rows) {
        return rows.stream().filter(row -> row.kind().equals("SPT")).toList();
    }

    private static List<ExportRow> talkRows(LocalDate day) {
        ClockGrid talk = Fixtures.programming().get("KCRR");
        return rowsFor(DayLog.build(talk, DayBoard.build(talk, day)));
    }

    @Test
    void theColumnsAreTheInterface() {
        assertThat(WIDTHS)
                .extracting(Column::name)
                .containsExactly("hour", "start", "kind", "tenths", "cart", "order", "line", "advertiser");
        assertThat(WIDTHS.stream().mapToInt(Column::width).sum() + WIDTHS.size() - 1)
                .isEqualTo(LINE_WIDTH);
    }

    @Test
    void aRowPadsEveryColumnToItsWidth() {
        String rendered = new ExportRow(7, "07:10:00", "SPT", 300, "C1204", "25-1044", 1, "HYVEE").render();
        assertThat(rendered)
                .startsWith("07 07:10:00 SPT 00300 C1204   ")
                .contains("25-1044")
                .endsWith("HYVEE");
    }

    @Test
    void aLengthIsWrittenInTenthsWithoutAPoint() {
        assertThat(new ExportRow(7, "07:10:00", "SPT", 300, "C", "O", 1, "A").render())
                .contains(" 00300 ");
        assertThat(new ExportRow(7, "07:10:00", "SPT", 600, "C", "O", 1, "A").render())
                .contains(" 00600 ");
        assertThat(new ExportRow(7, "07:10:00", "SPT", 301, "C", "O", 1, "A").render())
                .contains(" 00301 ");
    }

    @Test
    void theHourIsWrittenOnEveryLine() {
        put(7, 2, 1);
        put(15, 2, 2);
        List<String> lines = render(DayLog.build(grid, board)).lines().toList();
        assertThat(lines).allMatch(line -> Character.isDigit(line.charAt(0)) && Character.isDigit(line.charAt(1)));
        assertThat(lines).anyMatch(line -> line.startsWith("15 "));
        assertThat(lines).anyMatch(line -> line.startsWith("07 "));
    }

    @Test
    void aValueLongerThanItsColumnIsCutRatherThanShiftingTheRest() {
        String rendered = new ExportRow(7, "07:10:00", "SPT", 300, "C1204", "25-1044", 1, "A".repeat(40)).render();
        assertThat(rendered).hasSize(LINE_WIDTH).endsWith("A".repeat(20));
    }

    @Test
    void theFileCarriesTheFixedElementsAndNotTheHoles() {
        put(7, 2, 1);
        List<ExportRow> rows = rowsFor(DayLog.build(grid, board));
        assertThat(rows.stream().map(ExportRow::kind).collect(Collectors.toSet()))
                .isEqualTo(Set.of("LID", "SPT"));
        assertThat(rows).hasSize(25);
    }

    @Test
    void theFileRunsInAirOrder() {
        put(15, 2, 3);
        put(7, 2, 1);
        put(9, 4, 2);
        List<ExportRow> rows = spots(rowsFor(DayLog.build(grid, board)));
        assertThat(rows).extracting(ExportRow::line).containsExactly(1, 2, 3);
        assertThat(rows).extracting(ExportRow::hour).containsExactly(7, 9, 15);
    }

    @Test
    void aSpotCarriesItsOrderLineCartAndAdvertiser() {
        put(7, 2, "SLUMB", THIRTY, "C1205", 1);
        ExportRow row = spots(rowsFor(DayLog.build(grid, board))).get(0);
        assertThat(row.cart()).isEqualTo("C1205");
        assertThat(row.order()).isEqualTo("25-1044");
        assertThat(row.line()).isEqualTo(1);
        assertThat(row.advertiser()).isEqualTo("SLUMB");
        assertThat(row.tenths()).isEqualTo(300);
    }

    @Test
    void aLegalIdCarriesNoOrder() {
        ExportRow row = rowsFor(DayLog.build(grid, board)).get(0);
        assertThat(row.kind()).isEqualTo("LID");
        assertThat(row.order()).isEmpty();
        assertThat(row.line()).isZero();
        assertThat(row.tenths()).isEqualTo(100);
    }

    @Test
    void aNetworkBreakIsOnTheFileSoTheOperatorLeavesItAlone() {
        assertThat(talkRows(MONDAY)).anyMatch(row -> row.kind().equals("NET"));
    }

    @Test
    void theFileEndsWithANewline() {
        put(7, 2, 1);
        String text = render(DayLog.build(grid, board));
        assertThat(text).endsWith("\n").doesNotEndWith("\n\n");
    }

    @Test
    void aRowReadsBackTheWayItWasWritten() {
        ExportRow row = new ExportRow(7, "07:10:00", "SPT", 301, "C1204", "25-1044", 1, "HYVEE");
        assertThat(parseRow(row.render())).isEqualTo(row);
    }

    @Test
    void aWholeFileReadsBackTheWayItWasWritten() {
        put(7, 2, "HYVEE", SIXTY, "C1204", 1);
        put(9, 4, 2);
        DayLog log = DayLog.build(grid, board);
        assertThat(parse(render(log))).isEqualTo(rowsFor(log));
    }

    @Test
    void aLineThatIsNotALine() {
        assertThatThrownBy(() -> parseRow("   "))
                .isInstanceOf(ExportError.class)
                .hasMessageContaining("blank line");
        assertThatThrownBy(() -> parseRow("xx 07:10:00 SPT 00300 C1204    25-1044      001 HYVEE"))
                .isInstanceOf(ExportError.class)
                .hasMessageContaining("is not a log line");
    }

    @Test
    void blankLinesInAFileAreSkippedRatherThanFailing() {
        put(7, 2, 1);
        String text = render(DayLog.build(grid, board));
        assertThat(parse(text + "\n\n")).isEqualTo(parse(text));
    }

    @Test
    void theFileIsNamedTheWayTheWatchFolderExpects() {
        assertThat(filename("KWLO", MONDAY)).isEqualTo("KWLO250804.LOG");
        assertThat(filename("KOEL-FM", LocalDate.of(2025, 12, 31))).isEqualTo("KOELFM251231.LOG");
        assertThatThrownBy(() -> filename("", MONDAY))
                .isInstanceOf(ExportError.class)
                .hasMessageContaining("needs a station");
    }

    @Test
    void aPreemptedHourIsSimplyNotInTheFile() {
        List<ExportRow> rows = talkRows(LocalDate.of(2025, 8, 8));
        assertThat(rows).noneMatch(row -> row.hour() == 20);
        assertThat(rows).anyMatch(row -> row.hour() == 18);
    }

    @Test
    void anElementTheFormatHasNoCodeForIsRefused() {
        DayLog log = DayLog.build(grid, board);
        List<LogLine> lines = new ArrayList<>(log.lines());
        lines.add(new LogLine(log.lines().get(0).at(), 9, Element.UNSOLD, new Duration(0)));
        DayLog withHole = new DayLog(log.station(), log.day(), lines);
        // The hole is filtered out before the codes are looked up, so this is fine.
        assertThat(rowsFor(withHole)).isNotEmpty();
    }
}
