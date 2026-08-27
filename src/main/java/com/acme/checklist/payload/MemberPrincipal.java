package com.acme.checklist.payload;

public record MemberPrincipal(
        Long   memberId,
        String username,
        String role,
        Long   departmentId,
        String employeeId
) {
    public String departmentPrefix() {
        if (departmentId == null) return null;
        String code = String.valueOf(departmentId);
        if (code.length() <= 1) return code + "%";
        return code.substring(0, code.length() - 1) + "%";
    }
}