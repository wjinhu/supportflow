package dev.supportflow.order.domain.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Objects;

/** Gross CNY order amounts, not payment transactions or a refund-adjusted balance. */
public record OrderSpendingSummary(long orderCount, BigDecimal totalAmount, Instant from, Instant until) {
    public OrderSpendingSummary {
        Objects.requireNonNull(totalAmount);
        Objects.requireNonNull(from);
        Objects.requireNonNull(until);
        if (orderCount < 0 || totalAmount.signum() < 0 || !from.isBefore(until)
                || orderCount == 0 && totalAmount.signum() != 0) throw new IllegalArgumentException("Invalid spending summary");
        totalAmount = totalAmount.setScale(2, RoundingMode.UNNECESSARY);
    }
}
