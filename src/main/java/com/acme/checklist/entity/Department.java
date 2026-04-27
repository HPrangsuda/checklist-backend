package com.acme.checklist.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Table("department")
public class Department {
    @Id
    private Long id;

    @Column("business_unit")
    private String businessUnit;

    @Column("department")
    private String department;

    @Column("department_code")
    private String departmentCode;

    @Column("division")
    private String division;

    @Column("status")
    private String status;
}
