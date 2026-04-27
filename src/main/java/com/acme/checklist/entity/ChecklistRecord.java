package com.acme.checklist.entity;

import com.acme.checklist.entity.audit.DataAudit;
import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Table("checklist_record")
@EqualsAndHashCode(callSuper = true)
public class ChecklistRecord extends DataAudit {
    @Id
    private Long id;

    @Column("check_type")
    private String checkType;

    @Column("recheck")
    private Boolean recheck;

    @Column("machine_code")
    private String machineCode;

    @Column("machine_name")
    private String machineName;

    @Column("machine_status")
    private String machineStatus;

    @Column("machine_checklist")
    private String machineChecklist;

    @Column("machine_note")
    private String machineNote;

    @Column("image")
    private String image;

    @Column("user_id")
    private String userId;

    @Column("user_name")
    private String userName;

    @Column("supervisor")
    private String supervisor;

    @Column("date_supervisor_checked")
    private Instant dateSupervisorChecked;

    @Column("manager")
    private String manager;

    @Column("date_manager_checked")
    private Instant dateManagerChecked;

    @Column("checklist_status")
    private String checklistStatus;

    @Column("reason_not_checked")
    private String reasonNotChecked;

    @Column("job_detail")
    private String jobDetail;
}