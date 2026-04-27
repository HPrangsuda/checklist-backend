package com.acme.checklist.payload.calibration;

import com.acme.checklist.entity.CalibrationRecord;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class CalibrationResponseDTO {
    private Long id;
    private String machineCode;
    private String machineName;
    private Integer years;
    private LocalDate dueDate;
    private LocalDate startDate;
    private LocalDate certificateDate;
    private String results;
    private String criteria;
    private String measuringRange;
    private String accuracy;
    private String calibrationRange;
    private String calibrationStatus;
    private String attachment;
    private String note;
    private String permissibleCapacity;
    private String comment;
    private String resolution;
    private String maxUncertainty;
    private String mpe;
    private String checkMpe;
    private String checkResolution;
    private String checkResult;
    private String reasonNotPass;

    public static CalibrationResponseDTO from(CalibrationRecord record) {
        if (record == null) {
            return null;
        }

        return CalibrationResponseDTO.builder()
                .id(record.getId())
                .machineCode(record.getMachineCode())
                .machineName(record.getMachineName())
                .years(record.getYears())
                .dueDate(record.getDueDate())
                .startDate(record.getStartDate())
                .certificateDate(record.getCertificateDate())
                .results(record.getResults())
                .criteria(record.getCriteria())
                .measuringRange(record.getMeasuringRange())
                .accuracy(record.getAccuracy())
                .calibrationRange(record.getCalibrationRange())
                .calibrationStatus(record.getCalibrationStatus())
                .attachment(record.getAttachment())
                .note(record.getNote())
                .permissibleCapacity(record.getPermissibleCapacity())
                .comment(record.getComment())
                .resolution(record.getResolution())
                .maxUncertainty(record.getMaxUncertainty())
                .mpe(record.getMpe())
                .checkMpe(record.getCheckMpe())
                .checkResolution(record.getCheckResolution())
                .checkResult(record.getCheckResult())
                .reasonNotPass(record.getReasonNotPass())
                .build();
    }
}
