package com.acme.checklist.payload.kpi;

import com.acme.checklist.entity.Kpi;
import com.acme.checklist.payload.audit.AuditMemberDTO;
import com.acme.checklist.payload.checklist.ChecklistListDTO;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class KpiResponseDTO {
    private Long   id;
    private String employeeId;
    private String employeeName;
    private String years;
    private String months;
    private Long   checkAll;
    private Long   checked;
    private Long managerId;
    private Long supervisorId;

    private List<ChecklistListDTO> checklists;

    public static KpiResponseDTO from(Kpi kpi) {
        if (kpi == null) return null;
        return KpiResponseDTO.builder()
                .id(kpi.getId())
                .employeeId(kpi.getEmployeeId())
                .employeeName(kpi.getEmployeeName())
                .years(kpi.getYears())
                .months(kpi.getMonths())
                .checkAll(kpi.getCheckAll())
                .checked(kpi.getChecked())
                .managerId(kpi.getManagerId())
                .supervisorId(kpi.getSupervisorId())
                .build();
    }

    public static KpiResponseDTO from(Kpi kpi, List<ChecklistListDTO> checklists) {
        KpiResponseDTO dto = from(kpi);
        if (dto != null) dto.setChecklists(checklists);
        return dto;
    }
}