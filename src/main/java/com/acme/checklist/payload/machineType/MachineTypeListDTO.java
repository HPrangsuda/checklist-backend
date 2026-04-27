package com.acme.checklist.payload.machineType;

import com.acme.checklist.entity.MachineType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class MachineTypeListDTO {
    private Long id;
    private String machineGroupId;
    private String machineGroupName;
    private String machineTypeId;
    private String machineTypeName;
    private String status;

    public static MachineTypeListDTO from(MachineType machineType) {
        if (machineType == null) {
            return null;
        }

        return MachineTypeListDTO.builder()
                .id(machineType.getId())
                .machineGroupId(machineType.getMachineGroupId())
                .machineGroupName(machineType.getMachineGroupName())
                .machineTypeId(machineType.getMachineTypeId())
                .machineTypeName(machineType.getMachineTypeName())
                .status(machineType.getStatus())
                .build();
    }
}
