package io.github.rkaushikdev.cartwall.services;

import io.github.rkaushikdev.cartwall.Details;
import io.github.rkaushikdev.cartwall.persistence.models.CounterRow;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

/**
 * Hands out the next document number in a series, inside the caller's transaction, off a locked
 * counter row so there are no gaps.
 *
 * <p>An invoice number with a hole in it is an audit finding. The clever thing that does not work
 * is taking the highest number on file and adding one: two sellers writing an order at the same
 * moment both read 1043 and both write 1044. So there is one row per series per year, updated in
 * the same transaction as the document it numbers, with the row locked while it is read. If the
 * transaction rolls back the number goes back with it, which is what makes the sequence gapless
 * rather than merely unique.
 *
 * <p>Series are per year because that is how the office reads them: {@code 25-1044} is the forty
 * fourth order of 2025 after the first thousand, and in January the number goes back to 1001.
 */
public final class Numbering {

    /** Order numbers start here so a four digit number always reads as one. */
    public static final int FIRST = CounterRow.FIRST_NUMBER;

    /** Four digits after the dash, so 8999 documents of one kind in one year. */
    public static final int CEILING = 9999;

    private static final Pattern DIGITS = Pattern.compile("[0-9]+");

    private final EntityManager entityManager;

    public Numbering(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    /**
     * {@code 25-1044} back into a year and a sequence. Splits at the first dash, as the original
     * did, so {@code 25-10-44} is refused rather than read as two numbers.
     */
    public static DocumentNumber parseNumber(String number) {
        int dash = number.indexOf('-');
        String year = dash < 0 ? number : number.substring(0, dash);
        String sequence = dash < 0 ? "" : number.substring(dash + 1);
        if (!DIGITS.matcher(year).matches() || !DIGITS.matcher(sequence).matches()) {
            throw notANumber(number);
        }
        try {
            return new DocumentNumber(2000 + Integer.parseInt(year), Integer.parseInt(sequence));
        } catch (NumberFormatException tooLong) {
            throw notANumber(number);
        }
    }

    private static NumberingError notANumber(String number) {
        return new NumberingError("'" + number + "' is not a document number");
    }

    /** The next number, as it is written on the document. */
    public String next(String series, int year) {
        CounterRow row = counter(series, year);
        if (row.getLast() >= CEILING) {
            throw new SeriesExhausted(
                    "series " + series + " has used every number in " + year,
                    Details.of("series", series, "year", year));
        }
        row.setLast(row.getLast() + 1);
        entityManager.flush();
        return written(year, row.getLast());
    }

    /** What the counter stands at now. Reads without taking a number. */
    public int peek(String series, int year) {
        CounterRow row = entityManager.find(CounterRow.class, CounterRow.find(series, year));
        return row == null ? FIRST : row.getLast();
    }

    /** A block of numbers, for a run that writes many documents at once. */
    public List<String> reserve(String series, int year, int count) {
        if (count < 1) {
            throw new NumberingError(count + " is not a number of documents");
        }
        CounterRow row = counter(series, year);
        int left = CEILING - row.getLast();
        if (count > left) {
            throw new SeriesExhausted(
                    "series " + series + " has " + left + " numbers left in " + year + ", and " + count
                            + " were asked for",
                    Details.of("series", series, "year", year, "left", left));
        }
        int first = row.getLast() + 1;
        row.setLast(row.getLast() + count);
        entityManager.flush();
        return IntStream.rangeClosed(first, row.getLast())
                .mapToObj(number -> written(year, number))
                .toList();
    }

    /** Start a series somewhere other than the default. Migrations only. */
    public void openAt(String series, int year, int at) {
        if (at < FIRST) {
            throw new NumberingError("a series cannot open below " + FIRST);
        }
        CounterRow row = counter(series, year);
        if (row.getLast() > FIRST) {
            throw new NumberingError("series " + series + " has already issued numbers in " + year);
        }
        row.setLast(at);
        entityManager.flush();
    }

    private CounterRow counter(String series, int year) {
        if (series == null || series.isEmpty()) {
            throw new NumberingError("a series needs a code");
        }
        if (year < 2000 || year > 2999) {
            throw new NumberingError(year + " is not a year this house trades in");
        }
        CounterRow row =
                entityManager.find(CounterRow.class, CounterRow.find(series, year), LockModeType.PESSIMISTIC_WRITE);
        if (row == null) {
            row = new CounterRow(series, year);
            entityManager.persist(row);
            entityManager.flush();
        }
        return row;
    }

    private static String written(int year, int number) {
        return String.format(Locale.ROOT, "%02d-%d", year % 100, number);
    }

    /** A document number read back: the year it was issued in and its place in the series. */
    public record DocumentNumber(int year, int sequence) {}
}
