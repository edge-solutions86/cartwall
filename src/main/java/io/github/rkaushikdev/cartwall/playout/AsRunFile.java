package io.github.rkaushikdev.cartwall.playout;

import io.github.rkaushikdev.cartwall.AirTime;
import io.github.rkaushikdev.cartwall.Details;
import io.github.rkaushikdev.cartwall.Duration;
import io.github.rkaushikdev.cartwall.TimeError;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reading back what the automation says it actually played.
 *
 * <p>The as-run file is a different format from the log file and that is not an oversight: the
 * as-run export was added a decade later by different people, and it is comma separated with a
 * header row. Two formats is annoying and pretending they are one is worse, so they stay apart.
 */
public final class AsRunFile {

    /** The header the automation writes. Read, and checked, never assumed. */
    public static final List<String> HEADER = List.of("date", "time", "cart", "title", "length", "status", "reference");

    private static final DateTimeFormatter NAMED = DateTimeFormatter.ofPattern("yyMMdd", Locale.ROOT);

    private AsRunFile() {}

    /** The whole file, in the order the automation wrote it. */
    public static List<AsRun> parse(String text) {
        List<List<String>> records = Csv.records(text);
        if (records.isEmpty()) {
            throw new AsRunError("the as-run file is empty");
        }
        List<String> header = records.get(0).stream()
                .map(name -> name.strip().toLowerCase(Locale.ROOT))
                .toList();
        if (!header.equals(HEADER)) {
            throw new AsRunError(
                    "the as-run header is " + String.join(", ", header) + ", expected " + String.join(", ", HEADER),
                    Details.of("header", header));
        }
        List<AsRun> rows = new ArrayList<>();
        for (List<String> record : records.subList(1, records.size())) {
            Map<String, String> row = new LinkedHashMap<>();
            for (int index = 0; index < header.size() && index < record.size(); index++) {
                row.put(header.get(index), record.get(index));
            }
            rows.add(parseLine(row));
        }
        return List.copyOf(rows);
    }

    /** One line, by column name. A short line is missing the columns it did not reach. */
    public static AsRun parseLine(Map<String, String> row) {
        List<String> missing =
                HEADER.stream().filter(name -> row.get(name) == null).toList();
        if (!missing.isEmpty()) {
            throw new AsRunError(
                    "the as-run line is missing " + String.join(", ", missing), Details.of("missing", missing));
        }
        LocalDate day;
        try {
            day = LocalDate.parse(row.get("date").strip());
        } catch (DateTimeParseException notADate) {
            throw new AsRunError("'" + row.get("date") + "' is not a date");
        }
        AirTime at;
        try {
            at = AirTime.parse(row.get("time"));
        } catch (TimeError notATime) {
            throw new AsRunError("'" + row.get("time") + "' is not an air time");
        }
        return new AsRun(
                day,
                at,
                row.get("cart").strip(),
                row.get("title").strip(),
                length(row.get("length")),
                Played.written(row.get("status")),
                row.get("reference").strip());
    }

    /** The name the watch folder writes the as-run under, such as {@code KWLO250804.ASR}. */
    public static String filename(String station, LocalDate day) {
        if (station.isEmpty()) {
            throw new AsRunError("an as-run file needs a station");
        }
        return station.replace("-", "") + NAMED.format(day) + ".ASR";
    }

    /** The lines that carry one of our references. The rest is programming. */
    public static List<AsRun> spotsOnly(List<AsRun> rows) {
        return rows.stream().filter(row -> row.key().isPresent()).toList();
    }

    /** How many lines of each status, by status name. */
    public static Map<Played, Integer> byStatus(List<AsRun> rows) {
        Map<Played, Integer> tally = new EnumMap<>(Played.class);
        rows.forEach(row -> tally.merge(row.status(), 1, Integer::sum));
        Map<Played, Integer> ordered = new LinkedHashMap<>();
        tally.entrySet().stream()
                .sorted(Comparator.comparing(entry -> entry.getKey().value()))
                .forEach(entry -> ordered.put(entry.getKey(), entry.getValue()));
        return ordered;
    }

    /** A wall clock moment, for anything that wants to sort across days. */
    public static LocalDateTime stamp(LocalDate day, AirTime at) {
        return day.atTime(at.hour(), at.minute(), at.second());
    }

    /** The automation writes seconds with one decimal, or bare tenths. */
    static Duration length(String text) {
        String cleaned = text.strip();
        if (cleaned.isEmpty()) {
            throw new AsRunError("an as-run line has no length");
        }
        int point = cleaned.indexOf('.');
        if (point >= 0) {
            String whole = cleaned.substring(0, point);
            String tenth = cleaned.substring(point + 1);
            if (!isDigits(whole) || tenth.length() != 1 || !isDigits(tenth)) {
                throw new AsRunError("'" + text + "' is not a length");
            }
            return new Duration(Integer.parseInt(whole) * 10 + Integer.parseInt(tenth));
        }
        if (!isDigits(cleaned)) {
            throw new AsRunError("'" + text + "' is not a length");
        }
        return new Duration(Integer.parseInt(cleaned));
    }

    private static boolean isDigits(String text) {
        return !text.isEmpty() && text.chars().allMatch(Character::isDigit);
    }
}
