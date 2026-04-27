package com.acme.checklist.payload.audit;

import com.acme.checklist.entity.Member;
import com.acme.checklist.entity.enums.RoleType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuditMemberDTO {
    private Long id;
    private String employeeId;
    private String firstName;
    private String lastName;
    private String avatarKey;
    private String email;
    private String userName;
    private String password;
    private RoleType roleType;

    public static AuditMemberDTO from(Member member) {
        return AuditMemberDTO.builder()
                .id(member.getId())
                .employeeId(member.getEmployeeId())
                .firstName(member.getFirstName())
                .lastName(member.getLastName())
                .avatarKey(member.getAvatarKey())
                .email(member.getEmail())
                .userName(member.getUserName())
                .password(member.getPassword())
                .roleType(member.getRoleType())
                .build();
    }
}