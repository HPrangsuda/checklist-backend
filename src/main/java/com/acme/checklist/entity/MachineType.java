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
@Table("machine_type")
@EqualsAndHashCode(callSuper = true)
public class MachineType extends DataAudit {
    @Id
    private Long id;

    @Column("machine_group_id")
    private String machineGroupId;

    @Column("machine_group_name")
    private String machineGroupName;

    @Column("machine_type_id")
    private String machineTypeId;

    @Column("machine_type_name")
    private String machineTypeName;

    @Column("status")
    private String status;
}
