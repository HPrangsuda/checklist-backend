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
    private Long      id;
    private String    machineCode;
    private String    machineName;
    private Integer   years;
    private LocalDate dueDate;
    private LocalDate startDate;
    private LocalDate certificateDate;
    private String    results;
    private String    criteria;
    private String    measuringRange;
    private String    accuracy;
    private String    calibrationRange;
    private String    calibrationStatus;
    private String    attachment;
    private String    note;
    private String    permissibleCapacity;
    private String    comment;
    private String    resolution;
    private String    maxUncertainty;
    private String    mpe;
    private String    checkMpe;
    private String    checkResolution;
    private String    checkResult;
    private String    reasonNotPass;
    private String    responsibleMaintenanceName;  // ★ machine.responsible_person_name
    private String    machineDepartmentCode;        // ★ machine.department
    private String    machineDepartmentName;        // ★ department.department

    public static CalibrationResponseDTO from(CalibrationRecord r) {
        if (r == null) return null;
        return CalibrationResponseDTO.builder()
                .id(r.getId())
                .machineCode(r.getMachineCode())
                .machineName(r.getMachineName())
                .years(r.getYears())
                .dueDate(r.getDueDate())
                .startDate(r.getStartDate())
                .certificateDate(r.getCertificateDate())
                .results(r.getResults())
                .criteria(r.getCriteria())
                .measuringRange(r.getMeasuringRange())
                .accuracy(r.getAccuracy())
                .calibrationRange(r.getCalibrationRange())
                .calibrationStatus(r.getCalibrationStatus())
                .attachment(r.getAttachment())
                .note(r.getNote())
                .permissibleCapacity(r.getPermissibleCapacity())
                .comment(r.getComment())
                .resolution(r.getResolution())
                .maxUncertainty(r.getMaxUncertainty())
                .mpe(r.getMpe())
                .checkMpe(r.getCheckMpe())
                .checkResolution(r.getCheckResolution())
                .checkResult(r.getCheckResult())
                .reasonNotPass(r.getReasonNotPass())
                .build();
    }

    public static CalibrationResponseDTO from(CalibrationRecord r, String responsibleName) {
        CalibrationResponseDTO dto = from(r);
        if (dto != null) dto.setResponsibleMaintenanceName(responsibleName);
        return dto;
    }
}