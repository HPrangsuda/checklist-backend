package com.acme.checklist.payload.machine;

import com.acme.checklist.payload.calibration.CalibrationDTO;
import com.acme.checklist.payload.maintenance.MaintenanceDTO;
import lombok.*;

import java.time.LocalDate;
import java.util.List;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class MachineDTO {

    private Long id;
    private String machineCode;
    private String machineName;
    private String machineGroupId;
    private String machineTypeId;
    private String machineStatus;
    private String checkStatus;
    private String model;
    private String brand;
    private String serialNumber;
    private String businessUnit;
    private String department;
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
    private String responsiblePersonId;
    private String responsiblePersonName;
    private String supervisorId;
    private String managerId;
    private String workInstruction;
    private LocalDate lastReview;
    private String reviewBy;

    private CalibrationDTO calibration;
    private List<MaintenanceDTO> maintenanceList;
}