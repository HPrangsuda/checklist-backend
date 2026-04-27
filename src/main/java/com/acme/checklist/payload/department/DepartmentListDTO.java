package com.acme.checklist.payload.department;

import com.acme.checklist.entity.Department;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class DepartmentListDTO {
    private Long id;
    private String department;
    private String departmentCode;
    private String division;

    public static DepartmentListDTO from(Department department) {
        if (department == null) {
            return null;
        }

        return DepartmentListDTO.builder()
                .id(department.getId())
                .department(department.getDepartment())
                .departmentCode(department.getDepartmentCode())
                .division(department.getDivision())
                .build();
    }
}
