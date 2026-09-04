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
    private long   total;
    private long   totalReadyToUse;
    private long   totalUnderMaintenance;
    private double readyRate;
}