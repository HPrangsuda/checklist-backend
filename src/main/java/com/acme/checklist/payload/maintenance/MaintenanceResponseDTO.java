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

    public static MaintenanceResponseDTO from(MaintenanceRecord maintenanceRecord) {
        if (maintenanceRecord == null) {
            return null;
        }

        return MaintenanceResponseDTO.builder()
                .id(maintenanceRecord.getId())
                .machineCode(maintenanceRecord.getMachineCode())
                .machineName(maintenanceRecord.getMachineName())
                .years(maintenanceRecord.getYears())
                .round(maintenanceRecord.getRound())
                .dueDate(maintenanceRecord.getDueDate())
                .planDate(maintenanceRecord.getPlanDate())
                .startDate(maintenanceRecord.getStartDate())
                .actualDate(maintenanceRecord.getActualDate())
                .status(maintenanceRecord.getStatus())
                .maintenanceBy(maintenanceRecord.getMaintenanceBy())
                .responsibleMaintenance(maintenanceRecord.getResponsibleMaintenance())
                .note(maintenanceRecord.getNote())
                .attachment(maintenanceRecord.getAttachment())
                .build();
    }
}
