package com.acme.checklist.payload.calibration;

import com.acme.checklist.entity.CalibrationRecord;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CalibrationListDTO {
    private Long      id;
    private String    machineCode;
    private String    machineName;
    private String    years;
    private LocalDate dueDate;
    private LocalDate startDate;
    private LocalDate certificateDate;
    private String    results;
    private String    calibrationStatus;

    public static CalibrationListDTO from(CalibrationRecord r) {
        if (r == null) return null;
        return CalibrationListDTO.builder()
                .id(r.getId())
                .machineCode(r.getMachineCode())
                .machineName(r.getMachineName())
                .years(r.getYears())
                .dueDate(r.getDueDate())
                .startDate(r.getStartDate())
                .certificateDate(r.getCertificateDate())
                .results(r.getResults())
                .calibrationStatus(r.getCalibrationStatus())
                .build();
    }
}