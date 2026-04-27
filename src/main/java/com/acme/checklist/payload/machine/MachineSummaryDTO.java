package com.acme.checklist.payload.machine;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MachineSummaryDTO {
    private String department;
    private String departmentName;
    private long total;
    private long totalReadyToUse;
    private long totalRepair;
    private long totalNotInUse;
    private long totalCompleted;
    private long totalPending;
    private long totalApprove;
    private double readyRate;
    private double completedRate;
    private double approveRate;
}