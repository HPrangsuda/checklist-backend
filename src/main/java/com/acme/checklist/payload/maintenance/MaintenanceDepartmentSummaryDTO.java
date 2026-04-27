package com.acme.checklist.payload.maintenance;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class MaintenanceDepartmentSummaryDTO {
    private String department;
    private String departmentName;
    private Long total;
    private Long totalPass;
    private Long totalNotPass;
    private Long totalOnTime;
    private Long totalOverdue;
    private Long totalCompleted;
    private Long totalPending;

    public double getPassRate() {
        return total != null && total > 0 ? (totalPass * 100.0) / total : 0.0;
    }

    public double getOnTimeRate() {
        return total != null && total > 0 ? (totalOnTime * 100.0) / total : 0.0;
    }

    public double getCompletedRate() {
        return total != null && total > 0 ? (totalCompleted * 100.0) / total : 0.0;
    }
}