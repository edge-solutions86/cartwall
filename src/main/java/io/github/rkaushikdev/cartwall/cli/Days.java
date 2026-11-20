package io.github.rkaushikdev.cartwall.cli;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import picocli.CommandLine.ITypeConverter;
import picocli.CommandLine.TypeConversionException;

/** A day on the command line, written {@code YYYY-MM-DD}, and nothing else. */
public final class Days implements ITypeConverter<LocalDate> {

    @Override
    public LocalDate convert(String text) {
        return parseDay(text);
    }

    /** Read a day, saying the shape wanted when it is not one. */
    public static LocalDate parseDay(String text) {
        try {
            return LocalDate.parse(text);
        } catch (DateTimeParseException notADay) {
            throw new TypeConversionException("'" + text + "' is not a date, use YYYY-MM-DD");
        }
    }
}
