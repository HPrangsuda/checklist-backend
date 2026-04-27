package com.acme.checklist.payload.maintenance;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class MaintenanceSaveDTO {
    private Long maintenanceRecordId;
    private String machineCode;
    private String machineName;
    private String machineStatus;
    private String machineChecklist;
    private String machineNote;
    private String image;
    private String userId;
    private String userName;
    private String supervisor;
    private String manager;
    private String jobDetail;
    private LocalDate actualDate;
    private LocalDate dueDate;
    private String maintenanceBy;
    private String responsibleMaintenance;
}