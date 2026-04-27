package com.acme.checklist.payload.kpi;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class KpiDTO {
    private Long id;
    private String employeeId;
    private String employeeName;
    private String years;
    private String months;
    private Long checkAll;
    private Long checked;
    private String managerId;
    private String supervisorId;
}
