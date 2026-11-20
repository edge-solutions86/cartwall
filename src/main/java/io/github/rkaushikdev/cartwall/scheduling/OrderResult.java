package io.github.rkaushikdev.cartwall.scheduling;

import io.github.rkaushikdev.cartwall.BroadcastWeek;
import io.github.rkaushikdev.cartwall.domain.Order;
import java.util.List;

/**
 * What a whole order came to in one week.
 *
 * @param order the order
 * @param week the broadcast week
 * @param lines each line's result, by line number
 */
public record OrderResult(Order order, BroadcastWeek week, List<LineResult> lines) {

    public OrderResult {
        lines = List.copyOf(lines);
    }

    public int wanted() {
        return lines.stream().mapToInt(LineResult::wanted).sum();
    }

    public int placed() {
        return lines.stream().mapToInt(LineResult::placed).sum();
    }

    public boolean isComplete() {
        return lines.stream().allMatch(LineResult::isComplete);
    }

    public List<LineResult> shortfalls() {
        return lines.stream().filter(line -> !line.isComplete()).toList();
    }

    @Override
    public String toString() {
        return order.number() + " " + placed() + " of " + wanted() + " in " + week;
    }
}
