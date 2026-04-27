package com.acme.checklist.payload.dashboard;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SummaryDTO {
    private Long total;
    private Long totalMaintenance;
    private Long totalCalibration;
    private Long totalAvailable;
}

