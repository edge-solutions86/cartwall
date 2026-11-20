package io.github.rkaushikdev.cartwall.playout;

import io.github.rkaushikdev.cartwall.Details;
import io.github.rkaushikdev.cartwall.Duration;
import io.github.rkaushikdev.cartwall.logs.DayLog;
import io.github.rkaushikdev.cartwall.logs.Element;
import io.github.rkaushikdev.cartwall.logs.LogLine;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Handing the log to the automation, in the format it will actually read.
 *
 * <p>A flat text file, one line per element, fixed width, no header and no footer. Three things
 * about it have cost somebody an evening: the hour is repeated on every line, because the
 * automation loads an hour at a time; lengths are in tenths written without the point, so {@code
 * 300} is a thirty; and the order of the columns is the interface, because there is no header to
 * disagree with. The file is written in air order and nothing sorts it afterwards.
 */
public final class LogExport {

    /** Column widths, in order. Changing one of these is a change to the interface. */
    public static final List<Column> WIDTHS = List.of(
            new Column("hour", 2),
            new Column("start", 8),
            new Column("kind", 3),
            new Column("tenths", 5),
            new Column("cart", 8),
            new Column("order", 12),
            new Column("line", 3),
            new Column("advertiser", 20));

    public static final int LINE_WIDTH = WIDTHS.stream().mapToInt(Column::width).sum() + WIDTHS.size() - 1;

    /** What the automation calls each kind of element. Holes have no code; they are not sent. */
    static final Map<Element, String> KINDS = kinds();

    private static final DateTimeFormatter NAMED = DateTimeFormatter.ofPattern("yyMMdd", Locale.ROOT);

    private LogExport() {}

    /** Every element the automation is handed, in air order. Holes are not. */
    public static List<ExportRow> rowsFor(DayLog log) {
        List<ExportRow> rows = new ArrayList<>();
        for (LogLine entry : log.airable()) {
            String kind = KINDS.get(entry.element());
            if (kind == null) {
                throw new ExportError(
                        entry.element().value() + " has no code in the automation's format",
                        Details.of("element", entry.element().value()));
            }
            rows.add(new ExportRow(
                    entry.hour(),
                    entry.at().toString(),
                    kind,
                    entry.length().tenths(),
                    entry.cart(),
                    entry.order(),
                    entry.line(),
                    entry.advertiser()));
        }
        return List.copyOf(rows);
    }

    /** The whole file, newline separated, with a trailing newline. */
    public static String render(DayLog log) {
        StringBuilder text = new StringBuilder();
        rowsFor(log).forEach(row -> text.append(row.render()).append('\n'));
        return text.toString();
    }

    /** What the automation expects to find in the watch folder, such as {@code KWLO250804.LOG}. */
    public static String filename(String station, LocalDate day) {
        if (station.isEmpty()) {
            throw new ExportError("a log file needs a station");
        }
        return station.replace("-", "") + NAMED.format(day) + ".LOG";
    }

    /** Read one line back, as the import round trip does. */
    public static ExportRow parseRow(String text) {
        if (text.isBlank()) {
            throw new ExportError("a blank line is not a log line");
        }
        Map<String, String> values = new HashMap<>();
        int at = 0;
        for (Column column : WIDTHS) {
            int start = Math.min(at, text.length());
            int end = Math.min(at + column.width(), text.length());
            values.put(column.name(), text.substring(start, end).strip());
            at += column.width() + 1;
        }
        try {
            String line = values.get("line");
            return new ExportRow(
                    Integer.parseInt(values.get("hour")),
                    values.get("start"),
                    values.get("kind"),
                    Integer.parseInt(values.get("tenths")),
                    values.get("cart"),
                    values.get("order"),
                    line.isEmpty() ? 0 : Integer.parseInt(line),
                    values.get("advertiser"));
        } catch (NumberFormatException notALine) {
            throw new ExportError("'" + text + "' is not a log line");
        }
    }

    /** Every line of a file read back, blank lines skipped. */
    public static List<ExportRow> parse(String text) {
        return text.lines()
                .filter(line -> !line.isBlank())
                .map(LogExport::parseRow)
                .toList();
    }

    public static Duration lengthOf(int tenths) {
        return new Duration(tenths);
    }

    private static Map<Element, String> kinds() {
        Map<Element, String> kinds = new EnumMap<>(Element.class);
        kinds.put(Element.SPOT, "SPT");
        kinds.put(Element.LEGAL_ID, "LID");
        kinds.put(Element.NETWORK, "NET");
        kinds.put(Element.PROMO, "PRO");
        return Collections.unmodifiableMap(kinds);
    }
}
