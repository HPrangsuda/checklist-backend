package com.acme.checklist.payload.calibration;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CalibrationDepartmentSummaryDTO {
    private String department;           // Department code (e.g., "0501")
    private String departmentName;       // Department name (e.g., "Precision Measurement")
    private Long total;
    private Long totalPass;
    private Long totalNotPass;
    private Long totalOnTime;
    private Long totalOverdue;
    private Long totalCompleted;
    private Long totalPending;

    // Calculated fields - automatically computed by frontend
    public Double getPassRate() {
        if (total == null || total == 0) return 0.0;
        return (totalPass != null ? totalPass.doubleValue() : 0.0) / total * 100;
    }

    public Double getOnTimeRate() {
        if (total == null || total == 0) return 0.0;
        return (totalOnTime != null ? totalOnTime.doubleValue() : 0.0) / total * 100;
    }

    public Double getCompletedRate() {
        if (total == null || total == 0) return 0.0;
        return (totalCompleted != null ? totalCompleted.doubleValue() : 0.0) / total * 100;
    }

    public Double getNotPassRate() {
        if (total == null || total == 0) return 0.0;
        return (totalNotPass != null ? totalNotPass.doubleValue() : 0.0) / total * 100;
    }

    public Double getOverdueRate() {
        if (total == null || total == 0) return 0.0;
        return (totalOverdue != null ? totalOverdue.doubleValue() : 0.0) / total * 100;
    }

    public Double getPendingRate() {
        if (total == null || total == 0) return 0.0;
        return (totalPending != null ? totalPending.doubleValue() : 0.0) / total * 100;
    }
}