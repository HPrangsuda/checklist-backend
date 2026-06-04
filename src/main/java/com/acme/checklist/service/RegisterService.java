package com.acme.checklist.service;

import com.acme.checklist.entity.*;
import com.acme.checklist.exception.ThrowException;
import com.acme.checklist.payload.ApiResponse;
import com.acme.checklist.payload.MemberPrincipal;
import com.acme.checklist.payload.PagedResponse;
import com.acme.checklist.payload.audit.AuditMemberDTO;
import com.acme.checklist.payload.register.RegisterListDTO;
import com.acme.checklist.payload.register.RegisterDTO;
import com.acme.checklist.payload.register.RegisterResponseDTO;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.data.relational.core.query.Criteria;
import org.springframework.data.relational.core.query.Query;
import org.springframework.data.relational.core.query.Update;
import org.springframework.data.relational.core.sql.SqlIdentifier;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

import java.util.*;

@Slf4j
@Service
@AllArgsConstructor
public class RegisterService {
    private final R2dbcEntityTemplate template;
    private final CommonService commonService;
    private final ObjectMapper objectMapper;

    public Mono<ApiResponse<Void>> create(RegisterDTO dto) {
        return validateData(dto)
                .flatMap(validateDTO -> {
                    RegisterRequest registerRequest = buildFromDTO(validateDTO);
                    return commonService.save(registerRequest, RegisterRequest.class)
                            .then(Mono.just(ApiResponse.success("RG001")));
                })
                .onErrorResume(e -> {
                    log.error("Failed to create the register: {}", e.getMessage(), e);
                    return Mono.just(ApiResponse.error("RG002", e.getMessage()));
                });
    }

    public Mono<ApiResponse<Void>> update(RegisterDTO registerDTO) {
        return validateData(registerDTO)
                .flatMap(validateData -> {
                    Update update = buildUpdateFromDTO(validateData);
                    return commonService.update(registerDTO.getId(), update, RegisterRequest.class)
                            .then(Mono.just(ApiResponse.success("RG")));
                })
                .onErrorResume(e -> {
                    log.error("Failed to update the register: {}", e.getMessage());
                    return Mono.just(ApiResponse.error("MS004", e.getMessage()));
                });
    }

    public Mono<ApiResponse<Void>> delete(List<Long> ids) {
        return commonService.deleteEntitiesByIds(
                ids,
                RegisterRequest.class,
                "Register not found",
                "Register(s) deleted successfully",
                "Failed to delete register(s)",
                RegisterRequest::getMachineName,
                names -> Mono.empty()
        );
    }

    public Mono<PagedResponse<RegisterListDTO>> getWithRole(String keyword, int index, int size) {
        return ReactiveSecurityContextHolder.getContext()
                .mapNotNull(ctx -> (MemberPrincipal) Objects.requireNonNull(ctx.getAuthentication()).getPrincipal())
                .flatMap(principal -> {
                    String role       = principal.role();
                    String employeeId = principal.employeeId();
                    Long   memberId   = principal.memberId();

                    return switch (role) {
                        case "ADMIN" -> {
                            Criteria criteria = buildKeywordCriteria(keyword);
                            Query query = Query.query(criteria)
                                    .with(commonService.pageable(index, size, "created_at"));
                            yield commonService.executePagedQuery(
                                    index, size, query, criteria,
                                    RegisterRequest.class, this::convertRegisterListDTOs);
                        }
                        case "MANAGER" -> {
                            Criteria roleCriteria = Criteria
                                    .where("created_by").is(memberId)
                                    .or("manager_id").is(employeeId);
                            yield fetchWithRoleAndKeyword(roleCriteria, keyword, index, size);
                        }
                        case "SUPERVISOR" -> {
                            Criteria roleCriteria = Criteria
                                    .where("created_by").is(memberId)
                                    .or("supervisor_id").is(employeeId);
                            yield fetchWithRoleAndKeyword(roleCriteria, keyword, index, size);
                        }
                        default -> {
                            Criteria roleCriteria = Criteria
                                    .where("created_by").is(memberId);
                            yield fetchWithRoleAndKeyword(roleCriteria, keyword, index, size);
                        }
                    };
                });
    }

    private Mono<PagedResponse<RegisterListDTO>> fetchWithRoleAndKeyword(
            Criteria roleCriteria, String keyword, int index, int size) {
        Criteria criteria = roleCriteria;
        if (StringUtils.hasText(keyword)) {
            criteria = roleCriteria.and(
                    Criteria.where("machine_name").like("%" + keyword + "%").ignoreCase(true)
            );
        }
        Query query = Query.query(criteria).with(commonService.pageable(index, size, "created_at"));
        return commonService.executePagedQuery(
                index, size, query, criteria,
                RegisterRequest.class, this::convertRegisterListDTOs);
    }

    private Criteria buildKeywordCriteria(String keyword) {
        if (StringUtils.hasText(keyword)) {
            return Criteria.where("machine_name").like("%" + keyword + "%").ignoreCase(true);
        }
        return Criteria.empty();
    }

    public Mono<ApiResponse<RegisterResponseDTO>> getById(Long id) {
        Query query = Query.query(Criteria.where("id").is(id));

        return template.selectOne(query, RegisterRequest.class)
                .flatMap(registerRequest -> {
                    List<Long> auditIds = new ArrayList<>();
                    if (registerRequest.getCreatedBy() != null) auditIds.add(registerRequest.getCreatedBy());
                    if (registerRequest.getUpdatedBy()  != null) auditIds.add(registerRequest.getUpdatedBy());

                    Mono<Map<Long, Member>> auditMono = auditIds.isEmpty()
                            ? Mono.just(new HashMap<>())
                            : commonService.fetchMembersByIds(auditIds);

                    Mono<String> deptNameMono = Mono.justOrEmpty(registerRequest.getDepartment())
                            .flatMap(deptCode ->
                                    template.selectOne(
                                            Query.query(Criteria.where("department_code").is(deptCode)),
                                            Department.class
                                    ).map(Department::getDepartment)
                            )
                            .defaultIfEmpty(registerRequest.getDepartment() != null
                                    ? registerRequest.getDepartment() : "-");

                    Mono<String> responsibleMono = resolveMemberName(registerRequest.getResponsibleId());
                    Mono<String>  supervisorMono = resolveMemberName(registerRequest.getSupervisorId());
                    Mono<String>    managerMono  = resolveMemberName(registerRequest.getManagerId());

                    return Mono.zip(auditMono, deptNameMono, responsibleMono, supervisorMono, managerMono)
                            .map(tuple -> {
                                Map<Long, Member> memberMap = tuple.getT1();
                                String deptName            = tuple.getT2();
                                String responsibleName     = tuple.getT3();
                                String supervisorName      = tuple.getT4();
                                String managerName         = tuple.getT5();

                                AuditMemberDTO createdByDTO = registerRequest.getCreatedBy() != null
                                        ? AuditMemberDTO.from(memberMap.get(registerRequest.getCreatedBy()))
                                        : null;
                                AuditMemberDTO updatedByDTO = registerRequest.getUpdatedBy() != null
                                        ? AuditMemberDTO.from(memberMap.get(registerRequest.getUpdatedBy()))
                                        : null;

                                RegisterResponseDTO dto = RegisterResponseDTO.from(
                                        registerRequest, createdByDTO, updatedByDTO);

                                dto.setDepartmentName(deptName);
                                dto.setResponsibleName(responsibleName);
                                dto.setSupervisorName(supervisorName);
                                dto.setManagerName(managerName);

                                return ApiResponse.success("MS017", dto);
                            });
                })
                .switchIfEmpty(Mono.just(ApiResponse.error("MS018", "Data not found")))
                .onErrorResume(e -> {
                    log.error("Failed to fetch data: {}", e.getMessage(), e);
                    return Mono.just(ApiResponse.error("MS019", e.getMessage()));
                });
    }

    private Mono<String> resolveMemberName(String memberId) {
        if (!StringUtils.hasText(memberId)) return Mono.just("-");
        try {
            Long id = Long.parseLong(memberId);
            return template.selectOne(
                            Query.query(Criteria.where("id").is(id)), Member.class)
                    .map(m -> m.getFirstName() + " " + m.getLastName())
                    .defaultIfEmpty(memberId);
        } catch (NumberFormatException e) {
            return template.selectOne(
                            Query.query(Criteria.where("employee_id").is(memberId)), Member.class)
                    .map(m -> m.getFirstName() + " " + m.getLastName())
                    .defaultIfEmpty(memberId);
        }
    }

    private Flux<RegisterListDTO> convertRegisterListDTOs(List<RegisterRequest> registerRequests) {
        return Flux.fromIterable(registerRequests).map(RegisterListDTO::from);
    }

    public Mono<RegisterDTO> validateData(RegisterDTO registerDTO) {
        if (registerDTO.getDepartment() == null || registerDTO.getDepartment().isEmpty()) {
            return Mono.error(new ThrowException("RG003"));
        }
        return template.select(
                        Query.query(Criteria.where("department").is(registerDTO.getDepartment())),
                        RegisterRequest.class)
                .collectList()
                .flatMap(existing -> Mono.just(registerDTO));
    }

    public RegisterRequest buildFromDTO(RegisterDTO dto) {
        try {
            String maintenanceJson = null;
            if (dto.getMaintenance() != null && !dto.getMaintenance().isEmpty()) {
                maintenanceJson = objectMapper.writeValueAsString(dto.getMaintenance());
            }

            String calibrationJson = null;
            if (dto.getCalibration() != null && !dto.getCalibration().isEmpty()) {
                calibrationJson = objectMapper.writeValueAsString(dto.getCalibration());
            }

            // ── attachment: รูปภาพเท่านั้น ────────────────────────────────────
            String attachmentJson = null;
            if (dto.getAttachments() != null && !dto.getAttachments().isEmpty()) {
                attachmentJson = objectMapper.writeValueAsString(dto.getAttachments());
                log.info("✅ Attachments JSON: {}", attachmentJson);
            }

            // ── workInstruction: เอกสาร instruction แยก field ────────────────
            String workInstructionJson = null;
            if (dto.getWorkInstructions() != null && !dto.getWorkInstructions().isEmpty()) {
                workInstructionJson = objectMapper.writeValueAsString(dto.getWorkInstructions());
                log.info("✅ WorkInstruction JSON: {}", workInstructionJson);
            }

            // ── warrantyFiles: เอกสาร warranty แยก field ─────────────────────
            String warrantyFilesJson = null;
            if ("YES".equals(dto.getHasWarranty())
                    && dto.getWarrantyFiles() != null
                    && !dto.getWarrantyFiles().isEmpty()) {
                warrantyFilesJson = objectMapper.writeValueAsString(dto.getWarrantyFiles());
                log.info("✅ WarrantyFiles JSON: {}", warrantyFilesJson);
            }

            return RegisterRequest.builder()
                    .id(dto.getId())
                    .department(dto.getDepartment())
                    .machineName(dto.getMachineName())
                    .brand(dto.getBrand())
                    .model(dto.getModel())
                    .serialNumber(dto.getSerialNumber())
                    .price(dto.getPrice())
                    .quantity(dto.getQuantity())
                    .watt(dto.getWatt())
                    .horsePower(dto.getHorsePower())
                    .responsibleId(dto.getResponsibleId())
                    .supervisorId(dto.getSupervisorId())
                    .managerId(dto.getManagerId())
                    .note(dto.getNote())
                    .attachment(attachmentJson)
                    .workInstruction(workInstructionJson)
                    .maintenance(maintenanceJson)
                    .calibration(calibrationJson)
                    // ── warranty ──────────────────────────────────────────────
                    .hasWarranty(dto.getHasWarranty())
                    .warrantyNote("YES".equals(dto.getHasWarranty()) ? dto.getWarrantyNote() : null)
                    .warrantyExpireDate("YES".equals(dto.getHasWarranty()) ? dto.getWarrantyExpireDate() : null)
                    .warrantyFiles(warrantyFilesJson)
                    .build();

        } catch (Exception e) {
            throw new RuntimeException("Failed to process data: " + e.getMessage());
        }
    }

    private Update buildUpdateFromDTO(RegisterDTO dto) {
        Map<SqlIdentifier, Object> params = new HashMap<>();
        addIfNotNull(params, "department",    dto.getDepartment());
        addIfNotNull(params, "machine_name",  dto.getMachineName());
        addIfNotNull(params, "brand",         dto.getBrand());
        addIfNotNull(params, "model",         dto.getModel());
        addIfNotNull(params, "note",          dto.getNote());
        addIfNotNull(params, "price",         dto.getPrice());
        addIfNotNull(params, "quantity",      dto.getQuantity());
        addIfNotNull(params, "watt",          dto.getWatt());
        addIfNotNull(params, "horse_power",   dto.getHorsePower());
        addIfNotNull(params, "manager_id",    dto.getManagerId());

        // ── attachment (รูปภาพ) ───────────────────────────────────────────────
        if (dto.getAttachments() != null) {
            try {
                addIfNotNull(params, "attachment",
                        objectMapper.writeValueAsString(dto.getAttachments()));
            } catch (Exception e) {
                log.error("❌ Error converting attachments to JSON", e);
            }
        }

        // ── workInstruction ───────────────────────────────────────────────────
        if (dto.getWorkInstructions() != null) {
            try {
                params.put(SqlIdentifier.quoted("work_instruction"),
                        dto.getWorkInstructions().isEmpty()
                                ? null
                                : objectMapper.writeValueAsString(dto.getWorkInstructions()));
            } catch (Exception e) {
                log.error("❌ Error converting workInstructions to JSON", e);
            }
        }

        // ── warranty ──────────────────────────────────────────────────────────
        addIfNotNull(params, "has_warranty", dto.getHasWarranty());
        params.put(SqlIdentifier.quoted("warranty_note"),
                "YES".equals(dto.getHasWarranty()) ? dto.getWarrantyNote() : null);
        params.put(SqlIdentifier.quoted("warranty_expire_date"),
                "YES".equals(dto.getHasWarranty()) ? dto.getWarrantyExpireDate() : null);

        if (dto.getWarrantyFiles() != null) {
            try {
                params.put(SqlIdentifier.quoted("warranty_files"),
                        "YES".equals(dto.getHasWarranty()) && !dto.getWarrantyFiles().isEmpty()
                                ? objectMapper.writeValueAsString(dto.getWarrantyFiles())
                                : null);
            } catch (Exception e) {
                log.error("❌ Error converting warrantyFiles to JSON", e);
            }
        }

        return Update.from(params);
    }

    private void addIfNotNull(Map<SqlIdentifier, Object> params, String fieldName, Object value) {
        if (value != null) params.put(SqlIdentifier.quoted(fieldName), value);
    }
}