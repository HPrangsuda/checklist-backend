package com.acme.checklist.entity;

import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Table("kpi")
public class Kpi {
    @Id
    private Long id;

    @Column("employee_id")
    private String employeeId;

    @Column("employee_name")
    private String employeeName;

    @Column("years")
    private String years;

    @Column("months")
    private String months;

    @Column("check_all")
    private Long checkAll;

    @Column("checked")
    private Long checked;

    @Column("manager_id")
    private String managerId;

    @Column("supervisor_id")
    private String supervisorId;
}