package com.acme.checklist.payload.member;

import com.acme.checklist.entity.enums.RoleType;
import lombok.Data;

@Data
public class MemberDTO {
    private Long id;
    private String employeeId;
    private String departmentId;
    private String firstName;
    private String lastName;
    private String avatarKey;
    private String email;
    private String mobiles;
    private String userName;
    private String password;
    private RoleType roleType;
    private Long supervisor;
    private Long manager;
    private String languages;
    private String status;
}