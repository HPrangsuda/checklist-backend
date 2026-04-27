package com.acme.checklist.payload.register;

import com.acme.checklist.entity.Machine;
import com.acme.checklist.entity.RegisterRequest;
import com.acme.checklist.payload.audit.AuditMemberDTO;
import com.acme.checklist.payload.machine.MachineListDTO;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class RegisterListDTO {
    private Long id;
    private String machineName;
    private String brand;
    private String model;
    private String serialNumber;
    private Double price;
    private Integer quantity;
    private Integer watt;
    private Integer horsePower;
    private String department;
    private String responsibleId;
    private String supervisorId;
    private String managerId;
    private String note;
    private String attachment;
    private String maintenance;
    private String calibration;
    private AuditMemberDTO createdBy;
    private AuditMemberDTO updatedBy;

    public static RegisterListDTO from(RegisterRequest registerRequest) {
        if (registerRequest == null) {
            return null;
        }
        return RegisterListDTO.builder()
                .id(registerRequest.getId())
                .machineName(registerRequest.getMachineName())
                .model(registerRequest.getModel())

                .build();
    }
}