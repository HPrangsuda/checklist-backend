package com.acme.checklist.payload.register;

import com.acme.checklist.entity.RegisterRequest;
import com.acme.checklist.payload.audit.AuditMemberDTO;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

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
    private Long responsibleId;
    private Long supervisorId;
    private Long managerId;
    private String departmentName;
    private String responsibleName;
    private String supervisorName;
    private String managerName;

    private String note;
    private String attachment;
    private String workInstruction;
    private Object maintenance;
    private Object calibration;

    // ── warranty ──────────────────────────────────────────────────────────────
    private String    hasWarranty;
    private String    warrantyNote;
    private LocalDate warrantyExpireDate;
    private String    warrantyFiles;

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
                .workInstruction(r.getWorkInstruction())
                .maintenance(r.getMaintenance())
                .calibration(r.getCalibration())
                // ── warranty ──────────────────────────────────────────────────
                .hasWarranty(r.getHasWarranty())
                .warrantyNote(r.getWarrantyNote())
                .warrantyExpireDate(r.getWarrantyExpireDate())
                .warrantyFiles(r.getWarrantyFiles())
                .createdBy(createdBy)
                .updatedBy(updatedBy)
                .build();
    }
}