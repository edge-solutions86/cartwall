package io.github.rkaushikdev.cartwall.playout;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * One line of the automation's log file, before it is padded out.
 *
 * @param hour the hour, repeated on every line because the automation loads an hour at a time
 * @param start the air time, {@code 07:10:00}
 * @param kind the automation's code for the element, such as {@code SPT}
 * @param tenths the length in tenths, written without the point
 * @param cart the cart to play
 * @param order the order, for a spot
 * @param line the line, for a spot; zero otherwise
 * @param advertiser the advertiser, for a spot
 */
public record ExportRow(
        int hour, String start, String kind, int tenths, String cart, String order, int line, String advertiser) {

    /** The line, every value cut or padded to its column and the columns joined by one space. */
    public String render() {
        Map<String, String> values = Map.of(
                "hour", "%02d".formatted(hour),
                "start", start,
                "kind", kind,
                "tenths", "%05d".formatted(tenths),
                "cart", cart,
                "order", order,
                "line", "%03d".formatted(line),
                "advertiser", advertiser);
        List<String> pieces = new ArrayList<>();
        for (Column column : LogExport.WIDTHS) {
            String value = values.get(column.name());
            String cut = value.length() > column.width() ? value.substring(0, column.width()) : value;
            pieces.add(cut + " ".repeat(column.width() - cut.length()));
        }
        return String.join(" ", pieces).stripTrailing();
    }
}
