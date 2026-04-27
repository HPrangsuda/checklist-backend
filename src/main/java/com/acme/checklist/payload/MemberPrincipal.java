package com.acme.checklist.payload;

public record MemberPrincipal(
        Long memberId,
        String username,
        String role,
        Long departmentId,
        String employeeId
) { }