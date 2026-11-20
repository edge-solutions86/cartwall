package io.github.rkaushikdev.cartwall.playout;

import java.util.ArrayList;
import java.util.List;

/**
 * Comma separated values, read the way the automation's export writes them and Python's csv
 * module reads them: double quotes around a field that holds a comma, a doubled quote for a quote,
 * and a blank line skipped rather than read as an empty row.
 */
final class Csv {

    private Csv() {}

    /** Every non-blank record in {@code text}, each a list of its fields. */
    static List<List<String>> records(String text) {
        List<List<String>> records = new ArrayList<>();
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        boolean started = false;
        int index = 0;
        while (index < text.length()) {
            char next = text.charAt(index);
            if (quoted) {
                if (next == '"' && index + 1 < text.length() && text.charAt(index + 1) == '"') {
                    field.append('"');
                    index += 2;
                    continue;
                }
                if (next == '"') {
                    quoted = false;
                } else {
                    field.append(next);
                }
                index++;
                continue;
            }
            switch (next) {
                case '"' -> {
                    if (field.isEmpty()) {
                        quoted = true;
                    } else {
                        field.append(next);
                    }
                    started = true;
                }
                case ',' -> {
                    fields.add(field.toString());
                    field.setLength(0);
                    started = true;
                }
                case '\r', '\n' -> {
                    if (next == '\r' && index + 1 < text.length() && text.charAt(index + 1) == '\n') {
                        index++;
                    }
                    finish(records, fields, field, started);
                    fields = new ArrayList<>();
                    started = false;
                }
                default -> {
                    field.append(next);
                    started = true;
                }
            }
            index++;
        }
        finish(records, fields, field, started);
        return records;
    }

    private static void finish(List<List<String>> records, List<String> fields, StringBuilder field, boolean started) {
        if (started) {
            fields.add(field.toString());
            records.add(List.copyOf(fields));
        }
        field.setLength(0);
    }
}
