package com.acme.checklist.payload.maintenance;

import lombok.Builder;
import lombok.Data;
import java.util.List;

@Data
@Builder
public class MaintenanceMonthlyDTO {
    private int year;
    private int month;
    private long totalPlan;
    private long totalOnTime;   // actual IS NOT NULL AND actual <= due_date
    private long totalOverdue;  // (actual IS NOT NULL AND actual > due_date) OR actual IS NULL
    private List<ResponsibleSummary> byResponsible;

    @Data
    @Builder
    public static class ResponsibleSummary {
        private Long   memberId;
        private String memberName;
        private long   totalPlan;
        private long   totalOnTime;
        private long   totalOverdue;
    }
}