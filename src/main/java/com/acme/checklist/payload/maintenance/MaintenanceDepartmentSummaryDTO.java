package com.acme.checklist.payload.maintenance;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MaintenanceDepartmentSummaryDTO {

    private String department;
    private String departmentName;
    private Long   total;
    private Long   totalPass;
    private Long   totalNotPass;
    private Long   totalOnTime;
    private Long   totalOverdue;
    private Long   totalCompleted;
    private Long   totalPending;

    private Double passRate;
    private Double notPassRate;
    private Double onTimeRate;
    private Double overdueRate;
    private Double completedRate;
    private Double pendingRate;
}