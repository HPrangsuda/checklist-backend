package com.acme.checklist.payload.department;

import com.acme.checklist.entity.Department;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DepartmentResponseDTO {
    private Long id;
    private String businessUnit;
    private String department;
    private String departmentCode;
    private String division;
    private String status;

    public static DepartmentResponseDTO from(Department department) {
        if (department == null) {
            return null;
        }
        return DepartmentResponseDTO.builder()
                .id(department.getId())
                .businessUnit(department.getBusinessUnit())
                .department(department.getDepartment())
                .departmentCode(department.getDepartmentCode())
                .division(department.getDivision())
                .status(department.getStatus())
                .build();
    }
}
