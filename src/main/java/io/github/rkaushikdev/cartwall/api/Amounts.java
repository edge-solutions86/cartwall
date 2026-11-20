package io.github.rkaushikdev.cartwall.api;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import io.github.rkaushikdev.cartwall.CartwallError;
import io.github.rkaushikdev.cartwall.Money;
import io.github.rkaushikdev.cartwall.domain.SpotLength;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Money and lengths at the boundary, and why money is a string on the wire.
 *
 * <p>JSON numbers are IEEE doubles in every client anybody will point at this. A rate of {@code
 * 60.00} survives that and a running total of {@code 4835.15} does not always, and the failure is a
 * cent that appears in one client and not another against the same response. So every amount
 * crosses as a string in dollars, every length as a string in the form the log prints, and the
 * boundary converts. The arithmetic is done here, in integer cents.
 */
public final class Amounts {

    /** The words a decimal string may also be: not a number, and infinity, either sign. */
    private static final Pattern SPECIAL = Pattern.compile("[+-]?(nan|snan|inf|infinity)");

    private Amounts() {}

    /**
     * An amount as it crosses the wire. A string, a whole number or an exact decimal is accepted; a
     * floating point number is refused loudly.
     */
    public static String amount(Object value) {
        if (value instanceof Money money) {
            return money.amount().toPlainString();
        }
        if (value instanceof Double || value instanceof Float) {
            throw new IllegalArgumentException("send money as a string, not as a JSON number");
        }
        if (value instanceof Boolean flag) {
            return amount(flag ? 1 : 0);
        }
        if (value instanceof Integer || value instanceof Long || value instanceof BigInteger) {
            return new BigDecimal(value.toString())
                    .setScale(2, RoundingMode.HALF_EVEN)
                    .toPlainString();
        }
        if (value instanceof BigDecimal exact) {
            return exact.setScale(2, RoundingMode.HALF_EVEN).toPlainString();
        }
        if (value instanceof String text) {
            if (!isDecimal(text.replace(",", "").replaceFirst("^\\$+", ""))) {
                throw new IllegalArgumentException("'" + text + "' is not an amount");
            }
            return text;
        }
        throw new IllegalArgumentException(value + " is not an amount");
    }

    /** A length as it crosses the wire: the form the log prints, or whole seconds. */
    public static String length(Object value) {
        if (value instanceof SpotLength length) {
            return length.toString();
        }
        if (value instanceof Integer seconds) {
            return new SpotLength(seconds).toString();
        }
        if (value instanceof String text) {
            return text;
        }
        throw new IllegalArgumentException(value + " is not a length");
    }

    public static Money moneyOf(String text) {
        return Money.parse(text);
    }

    public static SpotLength lengthOf(String text) {
        return SpotLength.parse(text);
    }

    private static boolean isDecimal(String text) {
        String stripped = text.strip();
        if (SPECIAL.matcher(stripped.toLowerCase(Locale.ROOT)).matches()) {
            return true;
        }
        try {
            new BigDecimal(stripped);
            return true;
        } catch (NumberFormatException notANumber) {
            return false;
        }
    }

    /** What the parser is looking at, as the value the boundary rules are written against. */
    private static Object scalar(JsonParser in) throws IOException {
        JsonToken token = in.currentToken();
        return switch (token) {
            case VALUE_STRING -> in.getText();
            case VALUE_NUMBER_INT -> in.getNumberValue();
            case VALUE_NUMBER_FLOAT ->
                // Only an exact decimal handed in from code arrives as one; JSON text is a double.
                in.getNumberType() == JsonParser.NumberType.BIG_DECIMAL ? in.getDecimalValue() : in.getDoubleValue();
            case VALUE_TRUE -> Boolean.TRUE;
            case VALUE_FALSE -> Boolean.FALSE;
            default -> in.getText();
        };
    }

    /** Reads an amount field by {@link #amount}. */
    public static final class AmountJson extends JsonDeserializer<String> {

        @Override
        public String deserialize(JsonParser in, DeserializationContext context) throws IOException {
            try {
                return amount(scalar(in));
            } catch (IllegalArgumentException refused) {
                throw new Refused(in, refused.getMessage());
            }
        }

        @Override
        public String getNullValue(DeserializationContext context) throws JsonMappingException {
            throw new Refused(context.getParser(), "null is not an amount");
        }

        /** A field left out is a field required, which the record's constraints say. */
        @Override
        public Object getAbsentValue(DeserializationContext context) {
            return null;
        }
    }

    /** Reads a length field by {@link #length}. */
    public static final class LengthJson extends JsonDeserializer<String> {

        @Override
        public String deserialize(JsonParser in, DeserializationContext context) throws IOException {
            Object value = scalar(in);
            try {
                return length(
                        value instanceof Number number && in.currentToken() == JsonToken.VALUE_NUMBER_INT
                                ? Integer.valueOf(number.intValue())
                                : value);
            } catch (IllegalArgumentException | CartwallError refused) {
                throw new Refused(in, refused.getMessage());
            }
        }

        @Override
        public String getNullValue(DeserializationContext context) throws JsonMappingException {
            throw new Refused(context.getParser(), "null is not a length");
        }

        /** A field left out is a field required, which the record's constraints say. */
        @Override
        public Object getAbsentValue(DeserializationContext context) {
            return null;
        }
    }
}
