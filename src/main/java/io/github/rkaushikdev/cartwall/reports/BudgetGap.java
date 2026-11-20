package io.github.rkaushikdev.cartwall.reports;

import io.github.rkaushikdev.cartwall.Money;
import java.util.Optional;

/**
 * How far off budget a period came.
 *
 * @param gap revenue less budget; negative when short
 * @param fraction revenue as a fraction of budget, or nothing when the budget was nothing
 */
public record BudgetGap(Money gap, Optional<Double> fraction) {}
