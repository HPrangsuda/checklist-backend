package com.acme.checklist.payload;

import com.acme.checklist.payload.department.DepartmentListDTO;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SessionDTO {
    private Long memberId;
    private String employeeId;
    private String username;
    private String firstName;
    private String lastName;
    private String email;
    private String avatarKey;
    private String roleType;
    private String language;
    private String accessToken;
    private String refreshToken;
    private List<DepartmentListDTO> departments;
}