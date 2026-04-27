package com.acme.checklist.entity;

import com.acme.checklist.entity.audit.DataAudit;
import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDate;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Table("calibration_record")
@EqualsAndHashCode(callSuper = true)
public class CalibrationRecord extends DataAudit {
    @Id
    private Long id;

    @Column("machine_code")
    private String machineCode;

    @Column("machine_name")
    private String machineName;

    @Column("years")
    private Integer years;

    @Column("due_date")
    private LocalDate dueDate;

    @Column("start_date")
    private LocalDate startDate;

    @Column("certificate_date")
    private LocalDate certificateDate;

    @Column("results")
    private String results;

    @Column("criteria")
    private String criteria;

    @Column("measuring_range")
    private String measuringRange;

    @Column("accuracy")
    private String accuracy;

    @Column("calibration_range")
    private String calibrationRange;

    @Column("calibration_status")
    private String calibrationStatus;

    @Column("attachment")
    private String attachment;

    @Column("note")
    private String note;

    @Column("permissible_capacity")
    private String permissibleCapacity;

    @Column("comment")
    private String comment;

    @Column("resolution")
    private String resolution;

    @Column("max_uncertainty")
    private String maxUncertainty;

    @Column("mpe")
    private String mpe;

    @Column("check_mpe")
    private String checkMpe;

    @Column("check_resolution")
    private String checkResolution;

    @Column("check_result")
    private String checkResult;

    @Column("reason_not_pass")
    private String reasonNotPass;
}