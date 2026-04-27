package com.acme.checklist.payload.department;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class DepartmentDTO {
    private Long id;
    private String businessUnit;
    private String department;
    private String departmentCode;
    private String division;
    private String status;
}
