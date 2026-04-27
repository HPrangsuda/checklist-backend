package com.acme.checklist.entity;

import com.acme.checklist.entity.audit.DataAudit;
import com.acme.checklist.entity.enums.RoleType;
import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Table("member")
public class Member extends DataAudit {

    @Id
    private Long id;

    @Column("employee_id")
    private String employeeId;

    @Column("department_id")
    private String departmentId;

    @Column("first_name")
    private String firstName;

    @Column("last_name")
    private String lastName;

    @Column("avatar_key")
    private String avatarKey;

    @Column("email")
    private String email;

    @Column("mobiles")
    private String mobiles;

    @Column("user_name")
    private String userName;

    @Column("password")
    private String password;

    @Column("role_type")
    private RoleType roleType;

    @Column("languages")
    private String languages;

    @Column("status")
    private String status;
}