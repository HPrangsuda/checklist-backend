package com.acme.checklist.payload.maintenance;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.List;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class MaintenanceDTO {
    private Long id;
    private String machineCode;
    private String machineName;
    private String years;
    private Integer round;
    private LocalDate dueDate;
    private LocalDate planDate;
    private LocalDate startDate;
    private LocalDate actualDate;
    private String status;
    private String maintenanceBy;
    private String responsibleMaintenance;
    private String note;
    private String attachment;
    private String maintenanceType;
    private Integer checklist_record_id;
    private List<MaintenanceChDTO> checklistItems;
}
