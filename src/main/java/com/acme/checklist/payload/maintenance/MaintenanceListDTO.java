package com.acme.checklist.payload.maintenance;

import com.acme.checklist.entity.CalibrationRecord;
import com.acme.checklist.entity.MaintenanceRecord;
import com.acme.checklist.payload.calibration.CalibrationListDTO;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class MaintenanceListDTO {
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

    public static MaintenanceListDTO from(MaintenanceRecord maintenanceRecord) {
        if (maintenanceRecord == null) {
            return null;
        }
        return MaintenanceListDTO.builder()
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
                .build();
    }
}