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
public class MemberListDTO {
    private Long id;
    private String employeeId;
    private String firstName;
    private String lastName;
    private RoleType roleType;

    public static MemberListDTO from(Member member) {
        if (member == null) {
            return null;
        }
        return MemberListDTO.builder()
                .id(member.getId())
                .employeeId(member.getEmployeeId())
                .firstName(member.getFirstName())
                .lastName(member.getLastName())
                .roleType(RoleType.valueOf(member.getRoleType().name()))
                .build();
    }
}
