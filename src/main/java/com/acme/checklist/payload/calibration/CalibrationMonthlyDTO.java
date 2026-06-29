package com.acme.checklist.payload.calibration;

import lombok.Builder;
import lombok.Data;
import java.util.List;

@Data
@Builder
public class CalibrationMonthlyDTO {
    private int year;
    private int month;
    private long totalPlan;     // ทั้งหมดในเดือน (นับจาก due_date)
    private long totalOnTime;   // certificate_date IS NOT NULL AND certificate_date <= due_date
    private long totalOverdue;  // (certificate_date IS NOT NULL AND certificate_date > due_date)

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