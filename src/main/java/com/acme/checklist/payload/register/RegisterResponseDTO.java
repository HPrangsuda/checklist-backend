package com.acme.checklist.payload.register;

import com.acme.checklist.entity.RegisterRequest;
import com.acme.checklist.payload.audit.AuditMemberDTO;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RegisterResponseDTO {

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
    private String departmentName;
    private String responsibleName;
    private String supervisorName;
    private String managerName;

    private String note;
    private String attachment;
    private Object maintenance;
    private Object calibration;

    private AuditMemberDTO createdBy;
    private AuditMemberDTO updatedBy;

    public static RegisterResponseDTO from(RegisterRequest r,
                                           AuditMemberDTO createdBy,
                                           AuditMemberDTO updatedBy) {
        return RegisterResponseDTO.builder()
                .id(r.getId())
                .machineName(r.getMachineName())
                .brand(r.getBrand())
                .model(r.getModel())
                .serialNumber(r.getSerialNumber())
                .price(r.getPrice())
                .quantity(r.getQuantity())
                .watt(r.getWatt())
                .horsePower(r.getHorsePower())
                .department(r.getDepartment())
                .responsibleId(r.getResponsibleId())
                .supervisorId(r.getSupervisorId())
                .managerId(r.getManagerId())
                .note(r.getNote())
                .attachment(r.getAttachment())
                .maintenance(r.getMaintenance())
                .calibration(r.getCalibration())
                .createdBy(createdBy)
                .updatedBy(updatedBy)
                .build();
    }
}