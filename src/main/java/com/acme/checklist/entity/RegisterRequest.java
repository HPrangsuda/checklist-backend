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
@Table("register_request")
@EqualsAndHashCode(callSuper = true)
public class RegisterRequest extends DataAudit {
    @Id
    private Long id;

    @Column("machine_name")
    private String machineName;

    @Column("brand")
    private String brand;

    @Column("model")
    private String model;

    @Column("serial_number")
    private String serialNumber;

    @Column("price")
    private Double price;

    @Column("quantity")
    private Integer quantity;

    @Column("watt")
    private Integer watt;

    @Column("horse_power")
    private Integer horsePower;

    @Column("department")
    private String department;

    @Column("responsible_id")
    private String responsibleId;

    @Column("supervisor_id")
    private String supervisorId;

    @Column("manager_id")
    private String managerId;

    @Column("note")
    private String note;

    @Column("attachment")
    private String attachment;

    @Column("maintenance")
    private String maintenance;

    @Column("calibration")
    private String calibration;
}