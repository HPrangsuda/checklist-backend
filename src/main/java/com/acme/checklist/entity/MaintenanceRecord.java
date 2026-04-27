package com.acme.checklist.entity;

import com.acme.checklist.entity.audit.DataAudit;
import com.acme.checklist.entity.enums.MaintenanceType;
import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDate;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Table("maintenance_record")
@EqualsAndHashCode(callSuper = true)
public class MaintenanceRecord extends DataAudit {
    @Id
    private Long id;

    @Column("machine_code")
    private String machineCode;

    @Column("machine_name")
    private String machineName;

    @Column("years")
    private String years;

    @Column("round")
    private Integer round;

    @Column("due_date")
    private LocalDate dueDate;

    @Column("plan_date")
    private LocalDate planDate;

    @Column("start_date")
    private LocalDate startDate;

    @Column("actual_date")
    private LocalDate actualDate;

    @Column("status")
    private String status;

    @Column("maintenance_by")
    private String maintenanceBy;

    @Column("responsible_maintenance")
    private String responsibleMaintenance;

    @Column("note")
    private String note;

    @Column("attachment")
    private String attachment;

    @Column("maintenance_type")
    private MaintenanceType maintenanceType = MaintenanceType.PREVENTIVE;

    @Column("checklist_record_id")
    private Long checklistRecordId;
}