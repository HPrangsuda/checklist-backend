package com.acme.checklist.payload.dashboard;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MaintenanceStatsDTO {
    private String month;
    private Integer year;
    private Long on_time;
    private Long overdue;
}