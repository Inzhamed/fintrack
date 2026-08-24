package com.fintrack.api.dto.analytics;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Income against expense per calendar month, for the trend chart.
 *
 * @param points one entry per month in the range, including months with no activity -
 *               omitting empty months would draw a straight line across a gap and imply
 *               spending that never happened
 */
public record CashflowResponse(
        LocalDate from,
        LocalDate to,
        String currency,
        List<Point> points,
        BigDecimal netTotal
) {
    /**
     * @param label ISO year-month, e.g. "2026-08"
     * @param net   income minus expense for the month
     */
    public record Point(
            String label,
            int year,
            int month,
            BigDecimal income,
            BigDecimal expense,
            BigDecimal net
    ) {}
}
