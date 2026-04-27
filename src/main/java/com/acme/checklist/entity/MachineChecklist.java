package com.acme.checklist.entity;

import com.acme.checklist.entity.audit.DataAudit;
import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Table("machine_checklist")
@EqualsAndHashCode(callSuper = true)
public class MachineChecklist extends DataAudit {
    @Id
    private Long id;

    @Column("machine_code")
    private String machineCode;

    @Column("question_id")
    private Long questionId;

    @Column("is_choice")
    private Boolean isChoice;

    @Column("check_status")
    private Boolean checkStatus;

    @Column("reset_time")
    private String resetTime;
}