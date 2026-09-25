package com.acme.checklist.payload.member;

public record MemberOptionDTO(
        Long   id,
        String firstName,
        String lastName,
        String departmentId
) {}