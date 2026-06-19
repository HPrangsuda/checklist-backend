package com.acme.checklist.payload.maintenance;

import com.acme.checklist.entity.MaintenanceRecord;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class MaintenanceResponseDTO {
    private Long      id;
    private String    machineCode;
    private String    machineName;
    private String    years;
    private Integer   round;
    private LocalDate dueDate;
    private LocalDate planDate;
    private LocalDate startDate;
    private LocalDate actualDate;
    private String    status;
    private String    maintenanceBy;
    private Long      responsibleMaintenance;
    private String    responsibleMaintenanceName;
    private String    note;
    private String    attachment;
    private Long      checklistRecordId;
    private String machineDepartmentCode;
    private String machineDepartmentName;

    public static MaintenanceResponseDTO from(MaintenanceRecord r) {
        if (r == null) return null;
        return MaintenanceResponseDTO.builder()
                .id(r.getId())
                .machineCode(r.getMachineCode())
                .machineName(r.getMachineName())
                .years(r.getYears())
                .round(r.getRound())
                .dueDate(r.getDueDate())
                .planDate(r.getPlanDate())
                .startDate(r.getStartDate())
                .actualDate(r.getActualDate())
                .status(r.getStatus())
                .maintenanceBy(r.getMaintenanceBy())
                .responsibleMaintenance(r.getResponsibleMaintenance())
                .note(r.getNote())
                .attachment(r.getAttachment())
                .checklistRecordId(r.getChecklistRecordId())
                .build();
    }

    public static MaintenanceResponseDTO from(MaintenanceRecord r, String responsibleName) {
        MaintenanceResponseDTO dto = from(r);
        if (dto != null) dto.setResponsibleMaintenanceName(responsibleName);
        return dto;
    }
}