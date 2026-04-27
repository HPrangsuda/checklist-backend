package com.acme.checklist.payload.machineType;

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
}
