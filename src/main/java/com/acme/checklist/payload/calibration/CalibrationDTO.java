package com.acme.checklist.payload.calibration;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CalibrationDTO {
    private Long id;

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
}