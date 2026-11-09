package com.acme.checklist.payload.maintenance;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MaintenanceMonthlyDTO {

    private Integer year;
    private Integer month;

    /** จำนวนแผนทั้งหมด (ตาม due_date) */
    private Long totalPlan;

    /** ทำแล้ว และ actual_date <= due_date */
    private Long totalOnTime;

    /** ทำแล้วแต่ช้า หรือ ยังไม่ทำและเลยกำหนดแล้ว */
    private Long totalOverdue;

    /** ยังไม่ทำ และยังไม่ถึงกำหนด */
    private Long totalNotDue;

    private List<ResponsibleSummary> byResponsible;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ResponsibleSummary {
        private Long   memberId;
        private String memberName;
        private Long   totalPlan;
        private Long   totalOnTime;
        private Long   totalOverdue;
        private Long   totalNotDue;
    }
}