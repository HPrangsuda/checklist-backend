package com.acme.checklist.payload.register;

import com.acme.checklist.entity.Member;
import com.acme.checklist.entity.RegisterRequest;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RegisterListDTO {

    private Long   id;
    private String machineName;
    private String brand;
    private String model;
    private String serialNumber;
    private String department;
    private String createdAt;
    private CreatedByDTO createdBy;
    private CreatedByDTO updatedBy;

    // ── nested DTO ────────────────────────────────────────────────────────────
    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class CreatedByDTO {
        private Long   id;
        private String name;
    }

    // ── factory ───────────────────────────────────────────────────────────────

    /** ใช้เมื่อไม่มี member map (fallback) */
    public static RegisterListDTO from(RegisterRequest r) {
        return from(r, null, null);
    }

    /** ใช้ใน convertRegisterListDTOs หลังจาก join member แล้ว */
    public static RegisterListDTO from(RegisterRequest r, Member createdMember, Member updatedMember) {
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                .withZone(ZoneId.of("Asia/Bangkok"));

        CreatedByDTO createdByDTO = null;
        if (createdMember != null) {
            createdByDTO = new CreatedByDTO(
                    createdMember.getId(),
                    createdMember.getFirstName() + " " + createdMember.getLastName()
            );
        } else if (r.getCreatedBy() != null) {
            createdByDTO = new CreatedByDTO(r.getCreatedBy(), null);
        }

        CreatedByDTO updatedByDTO = null;
        if (updatedMember != null) {
            updatedByDTO = new CreatedByDTO(
                    updatedMember.getId(),
                    updatedMember.getFirstName() + " " + updatedMember.getLastName()
            );
        } else if (r.getUpdatedBy() != null) {
            updatedByDTO = new CreatedByDTO(r.getUpdatedBy(), null);
        }

        return RegisterListDTO.builder()
                .id(r.getId())
                .machineName(r.getMachineName())
                .brand(r.getBrand())
                .model(r.getModel())
                .serialNumber(r.getSerialNumber())
                .department(r.getDepartment())
                .createdAt(r.getCreatedAt() != null ? fmt.format(r.getCreatedAt()) : null)
                .createdBy(createdByDTO)
                .updatedBy(updatedByDTO)
                .build();
    }
}