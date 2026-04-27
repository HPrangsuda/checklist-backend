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
@Table("machine")
@EqualsAndHashCode(callSuper = true)
public class Machine extends DataAudit {

    @Id
    private Long id;

    @Column("machine_code")
    private String machineCode;

    @Column("machine_name")
    private String machineName;

    @Column("machine_group_id")
    private String machineGroupId;

    @Column("machine_type_id")
    private String machineTypeId;

    @Column("machine_status")
    private String machineStatus;

    @Column("check_status")
    private String checkStatus;

    @Column("model")
    private String model;

    @Column("brand")
    private String brand;

    @Column("serial_number")
    private String serialNumber;

    @Column("business_unit")
    private String businessUnit;

    @Column("department")
    private String department;

    @Column("register_id")
    private String registerId;

    @Column("register_date")
    private LocalDate registerDate;

    @Column("cancel_date")
    private LocalDate cancelDate;

    @Column("reason_cancel")
    private String reasonCancel;

    @Column("is_calibration")
    private Boolean calibration;

    @Column("certificate_period")
    private String certificatePeriod;

    @Column("maintenance_period")
    private String maintenancePeriod;

    @Column("image")
    private String image;

    @Column("responsible_person_id")
    private String responsiblePersonId;

    @Column("responsible_person_name")
    private String responsiblePersonName;

    @Column("supervisor_id")
    private String supervisorId;

    @Column("manager_id")
    private String managerId;

    @Column("work_instruction")
    private String workInstruction;

    @Column("machine_number")
    private String machineNumber;

    @Column("qr_code")
    private String qrCode;

    @Column("reset_period")
    private String resetPeriod;

    @Column("note")
    private String note;

    @Column("last_review")
    private LocalDate lastReview;

    @Column("review_by")
    private String reviewBy;

}