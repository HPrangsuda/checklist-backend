package com.acme.checklist.payload.machine;

import com.acme.checklist.entity.Machine;
import com.acme.checklist.payload.audit.AuditMemberDTO;
import com.acme.checklist.payload.calibration.CalibrationResponseDTO;
import com.acme.checklist.payload.maintenance.MaintenanceResponseDTO;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MachineResponseDTO {
    private Long id;
    private String machineCode;
    private String machineName;
    private String machineGroupId;
    private String machineGroupName;
    private String machineTypeId;
    private String machineTypeName;
    private String machineStatus;
    private String checkStatus;
    private String model;
    private String brand;
    private String serialNumber;
    private String businessUnit;
    private String department;
    private String departmentName;
    private String registerId;
    private LocalDate registerDate;
    private LocalDate cancelDate;
    private String reasonCancel;
    private Boolean isCalibration;
    private String certificatePeriod;
    private String maintenancePeriod;
    private String image;
    private String machineNumber;
    private String qrCode;
    private String resetPeriod;
    private String note;
    private Long responsiblePersonId;
    private String responsiblePersonName;
    private Long supervisorId;
    private String supervisorName;
    private Long managerId;
    private String managerName;
    private String workInstruction;
    private LocalDate lastReview;
    private String reviewBy;
    private AuditMemberDTO createdBy;
    private AuditMemberDTO updatedBy;

    private List<CalibrationResponseDTO> calibrationRecords;
    private List<MaintenanceResponseDTO> maintenanceRecords;

    public static MachineResponseDTO from(Machine machine, AuditMemberDTO createdBy, AuditMemberDTO updatedBy) {
        if (machine == null) return null;
        return MachineResponseDTO.builder()
                .id(machine.getId())
                .checkStatus(machine.getCheckStatus())
                .isCalibration(machine.getCalibration())
                .cancelDate(machine.getCancelDate())
                .department(machine.getDepartment())
                .machineGroupId(machine.getMachineGroupId())
                .image(machine.getImage())
                .machineCode(machine.getMachineCode())
                .model(machine.getModel())
                .brand(machine.getBrand())
                .machineName(machine.getMachineName())
                .machineNumber(machine.getMachineNumber())
                .machineStatus(machine.getMachineStatus())
                .machineTypeId(machine.getMachineTypeId())
                .maintenancePeriod(machine.getMaintenancePeriod())
                .managerId(machine.getManagerId())
                .qrCode(machine.getQrCode())
                .resetPeriod(machine.getResetPeriod())
                .responsiblePersonId(machine.getResponsiblePersonId())
                .responsiblePersonName(machine.getResponsiblePersonName())
                .serialNumber(machine.getSerialNumber())
                .supervisorId(machine.getSupervisorId())
                .workInstruction(machine.getWorkInstruction())
                .note(machine.getNote())
                .businessUnit(machine.getBusinessUnit())
                .registerId(machine.getRegisterId() != null ? machine.getRegisterId().toString() : null)
                .registerDate(machine.getRegisterDate())
                .certificatePeriod(machine.getCertificatePeriod())
                .createdBy(createdBy)
                .updatedBy(updatedBy)
                .build();
    }
}