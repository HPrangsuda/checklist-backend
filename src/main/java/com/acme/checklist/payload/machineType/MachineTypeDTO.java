package com.acme.checklist.payload.machineType;

import com.acme.checklist.entity.enums.EquipmentCategory;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class MachineTypeDTO {
    private Long id;
    private String machineGroupId;
    private String machineGroupName;
    private String machineTypeId;
    private String machineTypeName;
    private String status;

    private Boolean isMaintenance;
    private Boolean isCalibration;
    private EquipmentCategory equipmentCategory;
}