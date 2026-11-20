package io.github.rkaushikdev.cartwall;

import static io.github.rkaushikdev.cartwall.Duration.SECONDS_PER_HOUR;
import static io.github.rkaushikdev.cartwall.Duration.SECONDS_PER_MINUTE;
import static io.github.rkaushikdev.cartwall.Duration.TENTHS_PER_DAY;
import static io.github.rkaushikdev.cartwall.Duration.TENTHS_PER_SECOND;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A position in the broadcast day, in tenths of a second from midnight.
 *
 * <p>The broadcast day runs midnight to midnight and an air time cannot leave it. Overnight
 * programming that crosses the boundary belongs to the next day's log, which is what the
 * automation does and what the affidavit has to agree with. There is no time zone: 7:10 in the
 * log means 7:10 on the transmitter.
 *
 * @param tenths tenths of a second since midnight
 */
public record AirTime(int tenths) implements Comparable<AirTime> {

    /** The top of the broadcast day. */
    public static final AirTime MIDNIGHT = new AirTime(0);

    private static final Pattern WRITTEN =
            Pattern.compile("^(\\d{1,2}):(\\d{2})(?::(\\d{2}))?(?:\\.(\\d))?\\s*([ap])?$", Pattern.CASE_INSENSITIVE);

    public AirTime {
        if (tenths < 0 || tenths >= TENTHS_PER_DAY) {
            throw new TimeError(tenths + " tenths falls outside the broadcast day");
        }
    }

    public static AirTime at(int hour) {
        return at(hour, 0, 0);
    }

    public static AirTime at(int hour, int minute) {
        return at(hour, minute, 0);
    }

    public static AirTime at(int hour, int minute, int second) {
        if (hour < 0 || hour >= 24) {
            throw new TimeError("hour " + hour + " is not in the broadcast day");
        }
        if (minute < 0 || minute >= 60 || second < 0 || second >= 60) {
            throw new TimeError(hour + ":" + minute + ":" + second + " is not a clock time");
        }
        int total = hour * SECONDS_PER_HOUR + minute * SECONDS_PER_MINUTE + second;
        return new AirTime(total * TENTHS_PER_SECOND);
    }

    /**
     * {@code 6:00a}, {@code 2:15:30p} and {@code 14:15:30} are all the same shape.
     *
     * <p>Twelve hour with a trailing a or p is what the log prints and what the traffic director
     * types. Twenty four hour is what the automation sends.
     */
    public static AirTime parse(String text) {
        Matcher match = WRITTEN.matcher(text.strip());
        if (!match.matches()) {
            throw new TimeError("'" + text + "' is not an air time");
        }
        int hour = Integer.parseInt(match.group(1));
        String meridiem = match.group(5);
        if (meridiem != null) {
            if (hour < 1 || hour > 12) {
                throw new TimeError("'" + text + "' is not a twelve hour clock time");
            }
            hour = hour % 12;
            if (meridiem.toLowerCase(Locale.ROOT).equals("p")) {
                hour += 12;
            }
        }
        int second = match.group(3) == null ? 0 : Integer.parseInt(match.group(3));
        AirTime base = at(hour, Integer.parseInt(match.group(2)), second);
        int tenth = match.group(4) == null ? 0 : Integer.parseInt(match.group(4));
        return new AirTime(base.tenths + tenth);
    }

    /**
     * Start times for a run of elements that has to end exactly on {@code joinAt}.
     *
     * <p>Continuity backtimes the last break of an hour off the network join rather than forward
     * off the last song, because the join is the one time in the hour that cannot move.
     */
    public static List<AirTime> backtime(AirTime joinAt, List<Duration> lengths) {
        List<AirTime> starts = new ArrayList<>(lengths.size());
        AirTime cursor = joinAt;
        for (int index = lengths.size() - 1; index >= 0; index--) {
            cursor = cursor.minus(lengths.get(index));
            starts.add(cursor);
        }
        Collections.reverse(starts);
        return List.copyOf(starts);
    }

    public int hour() {
        return tenths / (SECONDS_PER_HOUR * TENTHS_PER_SECOND);
    }

    public int minute() {
        return tenths / (SECONDS_PER_MINUTE * TENTHS_PER_SECOND) % SECONDS_PER_MINUTE;
    }

    public int second() {
        return tenths / TENTHS_PER_SECOND % SECONDS_PER_MINUTE;
    }

    public AirTime topOfHour() {
        return at(hour());
    }

    /** How far past the top of the hour this sits. */
    public Duration intoHour() {
        return new Duration(tenths - topOfHour().tenths);
    }

    /** A length after a position is a position, as long as it stays on today's log. */
    public AirTime plus(Duration length) {
        int end = tenths + length.tenths();
        if (end >= TENTHS_PER_DAY) {
            throw new TimeError("that runs past midnight, which belongs to the next log");
        }
        return new AirTime(end);
    }

    /** A length before a position is a position, as long as it stays on today's log. */
    public AirTime minus(Duration length) {
        int start = tenths - length.tenths();
        if (start < 0) {
            throw new TimeError("that runs back before midnight");
        }
        return new AirTime(start);
    }

    /** Two positions are a length. Refuses when {@code earlier} is the later of the two. */
    public Duration minus(AirTime earlier) {
        return new Duration(tenths - earlier.tenths);
    }

    public boolean isBefore(AirTime other) {
        return tenths < other.tenths;
    }

    public boolean isAfter(AirTime other) {
        return tenths > other.tenths;
    }

    /** {@code 2:15:30p}, which is how the log prints for people. */
    public String twelveHour() {
        int printed = hour() % 12 == 0 ? 12 : hour() % 12;
        String meridiem = hour() < 12 ? "a" : "p";
        return "%d:%02d:%02d%s".formatted(printed, minute(), second(), meridiem);
    }

    @Override
    public int compareTo(AirTime other) {
        return Integer.compare(tenths, other.tenths);
    }

    /** {@code 14:15:30}, with a tenth when there is one. */
    @Override
    public String toString() {
        int tenth = tenths % TENTHS_PER_SECOND;
        String fraction = tenth == 0 ? "" : "." + tenth;
        return "%02d:%02d:%02d%s".formatted(hour(), minute(), second(), fraction);
    }
}
