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
    private String years;

    @Column("due_date")
    private LocalDate dueDate;

    @Column("start_date")
    private LocalDate startDate;

    @Column("certificate_date")
    private LocalDate certificateDate;

    @Column("mpe")
    private String mpe;

    @Column("internal_mpe")
    private String internalMpe;

    @Column("max_repeatability")
    private String maxRepeatability;

    @Column("resolution")
    private String resolution;

    @Column("min_capacity")
    private String minCapacity;

    @Column("max_capacity")
    private String maxCapacity;

    @Column("mpe_refer")
    private String mpeRefer;

    @Column("refer_standard")
    private String referStandard;

    @Column("calibration_by")
    private String calibrationBy;

    @Column("result")
    private String result;

    @Column("correction_factor")
    private String correctionFactor;

    @Column("measurement_range")
    private String measurementRange;

    @Column("remark")
    private String remark;

    @Column("attachment")
    private String attachment;

    @Column("is_canceled")
    private Boolean isCanceled = false;

    @Column("canceled_at")
    private LocalDate canceledAt;
}