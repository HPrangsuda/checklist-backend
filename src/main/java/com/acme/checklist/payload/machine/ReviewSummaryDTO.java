package com.acme.checklist.payload.machine;

public record ReviewSummaryDTO(
        int  year,
        int  month,
        int  remainingMonths,
        long totalActive,
        long poolAtMonthStart,
        int  quota,
        long reviewedThisYear,
        long reviewedThisMonth,
        int  pendingCount
) {}