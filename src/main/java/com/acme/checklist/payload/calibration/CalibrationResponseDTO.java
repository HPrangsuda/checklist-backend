package com.acme.checklist.payload.calibration;

import com.acme.checklist.entity.CalibrationRecord;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.ZoneId;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CalibrationResponseDTO {

    public static final String STATUS_ON_TIME = "On Time";
    public static final String STATUS_OVERDUE = "Overdue";
    public static final String STATUS_PENDING = "Pending";

    private static final ZoneId ZONE = ZoneId.of("Asia/Bangkok");

    private Long id;
    private String machineCode;
    private String machineName;
    private String years;

    private LocalDate dueDate;
    private LocalDate startDate;
    private LocalDate certificateDate;

    private String mpe;
    private String internalMpe;
    private String maxRepeatability;
    private String resolution;
    private String minCapacity;
    private String maxCapacity;
    private String mpeRefer;
    private String referStandard;
    private String calibrationBy;
    private String result;
    private String correctionFactor;
    private String measurementRange;
    private String remark;
    private String attachment;

    private Boolean isCanceled;
    private LocalDate canceledAt;

    private String calibrationStatus;

    private String responsibleMaintenanceName;
    private String machineDepartmentCode;
    private String machineDepartmentName;

    public static CalibrationResponseDTO from(CalibrationRecord c) {
        return CalibrationResponseDTO.builder()
                .id(c.getId())
                .machineCode(c.getMachineCode())
                .machineName(c.getMachineName())
                .years(c.getYears())
                .dueDate(c.getDueDate())
                .startDate(c.getStartDate())
                .certificateDate(c.getCertificateDate())
                .mpe(c.getMpe())
                .internalMpe(c.getInternalMpe())
                .maxRepeatability(c.getMaxRepeatability())
                .resolution(c.getResolution())
                .minCapacity(c.getMinCapacity())
                .maxCapacity(c.getMaxCapacity())
                .mpeRefer(c.getMpeRefer())
                .referStandard(c.getReferStandard())
                .calibrationBy(c.getCalibrationBy())
                .result(c.getResult())
                .correctionFactor(c.getCorrectionFactor())
                .measurementRange(c.getMeasurementRange())
                .remark(c.getRemark())
                .attachment(c.getAttachment())
                .isCanceled(c.getIsCanceled())
                .canceledAt(c.getCanceledAt())
                .calibrationStatus(statusOf(c.getDueDate(), c.getCertificateDate()))
                .build();
    }

    public static String statusOf(LocalDate due, LocalDate cert) {
        if (due == null) return null;
        if (cert != null) return cert.isAfter(due) ? STATUS_OVERDUE : STATUS_ON_TIME;
        return due.isBefore(LocalDate.now(ZONE)) ? STATUS_OVERDUE : STATUS_PENDING;
    }
}