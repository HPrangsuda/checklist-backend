package com.acme.checklist.payload.machine;

import com.acme.checklist.entity.Machine;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class MachineListDTO {
    private Long id;
    private String machineCode;
    private String machineName;
    private String department;
    private String machineStatus;
    private String checkStatus;
    private String responsiblePersonName;

    public static MachineListDTO from(Machine machine, String departmentName) {
        if (machine == null) {
            return null;
        }
        return MachineListDTO.builder()
                .id(machine.getId())
                .machineCode(machine.getMachineCode())
                .machineName(machine.getMachineName())
                .department(departmentName)
                .machineStatus(machine.getMachineStatus())
                .checkStatus(machine.getCheckStatus())
                .responsiblePersonName(machine.getResponsiblePersonName())
                .build();
    }
}
