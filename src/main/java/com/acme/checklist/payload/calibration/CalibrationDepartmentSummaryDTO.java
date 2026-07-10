package com.acme.checklist.payload.calibration;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CalibrationDepartmentSummaryDTO {

    private String department;
    private String departmentName;
    private Long   total;
    private Long   totalPass;
    private Long   totalNotPass;
    private Long   totalOnTime;
    private Long   totalOverdue;
    private Long   totalCompleted;
    private Long   totalPending;

    // ── Calculated fields — serialize เป็น JSON โดย Jackson ─────────────────

    @JsonProperty("passRate")
    public Double getPassRate() {
        if (total == null || total == 0) return 0.0;
        return (totalPass != null ? totalPass.doubleValue() : 0.0) / total * 100;
    }

    @JsonProperty("onTimeRate")
    public Double getOnTimeRate() {
        if (total == null || total == 0) return 0.0;
        return (totalOnTime != null ? totalOnTime.doubleValue() : 0.0) / total * 100;
    }

    @JsonProperty("completedRate")
    public Double getCompletedRate() {
        if (total == null || total == 0) return 0.0;
        return (totalCompleted != null ? totalCompleted.doubleValue() : 0.0) / total * 100;
    }

    @JsonProperty("notPassRate")
    public Double getNotPassRate() {
        if (total == null || total == 0) return 0.0;
        return (totalNotPass != null ? totalNotPass.doubleValue() : 0.0) / total * 100;
    }

    @JsonProperty("overdueRate")
    public Double getOverdueRate() {
        if (total == null || total == 0) return 0.0;
        return (totalOverdue != null ? totalOverdue.doubleValue() : 0.0) / total * 100;
    }

    @JsonProperty("pendingRate")
    public Double getPendingRate() {
        if (total == null || total == 0) return 0.0;
        return (totalPending != null ? totalPending.doubleValue() : 0.0) / total * 100;
    }
}