package com.acme.checklist.payload.register;

import com.acme.checklist.entity.audit.DataAudit;
import com.acme.checklist.payload.calibration.CalibrationDTO;
import com.acme.checklist.payload.file.FileUploadDTO;
import com.acme.checklist.payload.maintenance.MaintenanceDTO;
import lombok.*;

import java.util.List;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class RegisterDTO extends DataAudit {
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
    private List<FileUploadDTO> attachments;
    private List<MaintenanceDTO> maintenance;
    private List<CalibrationDTO> calibration;

    private Long createdBy;
    private Long updatedBy;
}
