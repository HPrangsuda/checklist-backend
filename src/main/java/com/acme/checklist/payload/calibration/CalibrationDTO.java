package com.acme.checklist.payload.calibration;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;

import java.time.LocalDate;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class CalibrationDTO {

    private Long id;
    private String machineCode;
    private String machineName;
    private String years;
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
}
