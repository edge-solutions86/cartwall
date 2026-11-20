package io.github.rkaushikdev.cartwall.domain;

import io.github.rkaushikdev.cartwall.BroadcastWeek;
import io.github.rkaushikdev.cartwall.Money;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * One contract with one advertiser, with every line on it.
 *
 * @param number the order number
 * @param advertiser the advertiser's code
 * @param written the day it was written
 * @param salesperson who wrote it
 * @param lines every line, in order
 * @param status pending, confirmed or cancelled
 * @param agency the agency that placed it, when there is one
 * @param campaign what the client calls it; prints on the confirmation and the invoice
 */
public record Order(
        String number,
        String advertiser,
        LocalDate written,
        String salesperson,
        List<Line> lines,
        OrderStatus status,
        Optional<String> agency,
        String campaign)
        implements Iterable<Line> {

    public Order {
        lines = List.copyOf(lines);
        Objects.requireNonNull(agency, "agency");
        if (number == null || number.isEmpty()) {
            throw new OrderError("an order needs a number");
        }
        if (advertiser == null || advertiser.isEmpty()) {
            throw new OrderError("order " + number + " names no advertiser");
        }
        Set<Integer> seen = new HashSet<>();
        Set<Integer> repeated = new TreeSet<>();
        for (Line line : lines) {
            if (!seen.add(line.number())) {
                repeated.add(line.number());
            }
        }
        if (!repeated.isEmpty()) {
            String joined = repeated.stream().map(String::valueOf).collect(Collectors.joining(", "));
            throw new OrderError("order " + number + " numbers line " + joined + " twice");
        }
    }

    /** A pending order, direct, with no campaign name. */
    public Order(String number, String advertiser, LocalDate written, String salesperson, List<Line> lines) {
        this(number, advertiser, written, salesperson, lines, OrderStatus.PENDING, Optional.empty(), "");
    }

    /** The line with this number. */
    public Line line(int number) {
        return lines.stream()
                .filter(line -> line.number() == number)
                .findFirst()
                .orElseThrow(() -> new OrderError("order " + this.number + " has no line " + number));
    }

    public int spots() {
        return lines.stream().mapToInt(Line::spots).sum();
    }

    public Money value() {
        return Money.total(lines.stream().map(Line::value).toList());
    }

    public LocalDate firstAir() {
        return lines.stream()
                .map(line -> line.flight().first())
                .min(Comparator.naturalOrder())
                .orElseThrow(() -> new OrderError("order " + number + " has no lines"));
    }

    public LocalDate lastAir() {
        return lines.stream()
                .map(line -> line.flight().last())
                .max(Comparator.naturalOrder())
                .orElseThrow(() -> new OrderError("order " + number + " has no lines"));
    }

    /** The stations the order buys, in line order, without repeats. */
    public List<String> stations() {
        Set<String> found = new LinkedHashSet<>();
        lines.forEach(line -> found.add(line.station()));
        return List.copyOf(found);
    }

    public List<Line> linesOn(String station) {
        return lines.stream().filter(line -> line.station().equals(station)).toList();
    }

    public Money valueIn(BroadcastWeek week) {
        return Money.total(lines.stream().map(line -> line.valueIn(week)).toList());
    }

    public Order confirmed() {
        if (status == OrderStatus.CANCELLED) {
            throw new OrderError("order " + number + " was cancelled and cannot be confirmed");
        }
        if (lines.isEmpty()) {
            throw new OrderError("order " + number + " has no lines to confirm");
        }
        return withStatus(OrderStatus.CONFIRMED);
    }

    public Order cancelled() {
        return withStatus(OrderStatus.CANCELLED);
    }

    public Order withLines(List<Line> lines) {
        return new Order(number, advertiser, written, salesperson, lines, status, agency, campaign);
    }

    public Order withAgency(String agency) {
        return new Order(
                number, advertiser, written, salesperson, lines, status, Optional.ofNullable(agency), campaign);
    }

    public Order withCampaign(String campaign) {
        return new Order(number, advertiser, written, salesperson, lines, status, agency, campaign);
    }

    private Order withStatus(OrderStatus status) {
        return new Order(number, advertiser, written, salesperson, lines, status, agency, campaign);
    }

    public int size() {
        return lines.size();
    }

    @Override
    public Iterator<Line> iterator() {
        return lines.iterator();
    }

    @Override
    public String toString() {
        return number + " " + advertiser + " " + value();
    }
}
