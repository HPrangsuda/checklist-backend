package com.acme.checklist.payload.machine;

import com.acme.checklist.entity.Machine;
import lombok.Builder;
import lombok.Data;

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
    private String qrCode;
    private String image;
    private String hasWarranty;

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
                .qrCode(machine.getQrCode())
                .image(machine.getImage())
                .hasWarranty(machine.getHasWarranty())
                .build();
    }
}