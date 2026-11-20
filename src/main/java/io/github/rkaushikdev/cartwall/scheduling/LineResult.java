package io.github.rkaushikdev.cartwall.scheduling;

import io.github.rkaushikdev.cartwall.BroadcastWeek;
import io.github.rkaushikdev.cartwall.domain.Line;

/**
 * What one line of an order came to in one week.
 *
 * @param line the line
 * @param week the broadcast week
 * @param result where its spots landed and which missed
 */
public record LineResult(Line line, BroadcastWeek week, Result result) {

    public int wanted() {
        return line.flight().countIn(week);
    }

    public int placed() {
        return result.placed();
    }

    public boolean isComplete() {
        return result.isComplete();
    }

    @Override
    public String toString() {
        return "line " + line.number() + " " + placed() + " of " + wanted();
    }
}
