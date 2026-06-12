package com.acme.checklist.payload.machine;

import com.acme.checklist.entity.Machine;
import lombok.Builder;
import lombok.Data;

/**
 * DTO สำหรับ list/table — ต้องมีทั้ง department (code) และ departmentName (name)
 *
 * department     → เก็บ department_code (เช่น "AD", "ST") ใช้ส่งไป filter
 * departmentName → เก็บชื่อแผนก (เช่น "Admin", "Store") ใช้แสดงใน table และ badge
 */
@Data
@Builder
public class MachineListDTO {

    private Long   id;
    private String machineCode;
    private String machineName;
    private String department;
    private String departmentName;
    private String machineStatus;
    private String checkStatus;
    private String responsiblePersonName;

    public static MachineListDTO from(Machine machine, String deptName) {
        return MachineListDTO.builder()
                .id(machine.getId())
                .machineCode(machine.getMachineCode())
                .machineName(machine.getMachineName())
                .department(machine.getDepartment())
                .departmentName(deptName)
                .machineStatus(machine.getMachineStatus())
                .checkStatus(machine.getCheckStatus())
                .responsiblePersonName(machine.getResponsiblePersonName())
                .build();
    }
}