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
    private Long id;
    private String machineCode;
    private String machineName;
    private Integer years;
    private LocalDate dueDate;
    private LocalDate startDate;
    private LocalDate certificateDate;
    private String results;
    private String calibrationStatus;

    public static CalibrationListDTO from(CalibrationRecord calibrationRecord) {
        if (calibrationRecord == null) {
            return null;
        }
        return CalibrationListDTO.builder()
                .id(calibrationRecord.getId())
                .machineCode(calibrationRecord.getMachineCode())
                .machineName(calibrationRecord.getMachineName())
                .years(calibrationRecord.getYears())
                .dueDate(calibrationRecord.getDueDate())
                .startDate(calibrationRecord.getStartDate())
                .certificateDate(calibrationRecord.getCertificateDate())
                .results(calibrationRecord.getResults())
                .calibrationStatus(calibrationRecord.getCalibrationStatus())
                .build();
    }
}
