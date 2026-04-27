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
import com.fasterxml.jackson.databind.ObjectMapper;
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
                    log.error("Failed to update the machine: {}", e.getMessage());
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
                .map(ctx -> (MemberPrincipal) ctx.getAuthentication().getPrincipal())
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
                            // เห็นของตัวเอง + ที่เป็น manager
                            Criteria roleCriteria = Criteria
                                    .where("created_by").is(memberId)
                                    .or("manager_id").is(employeeId);
                            yield fetchWithRoleAndKeyword(roleCriteria, keyword, index, size);
                        }
                        case "SUPERVISOR" -> {
                            // เห็นของตัวเอง + ที่เป็น supervisor
                            Criteria roleCriteria = Criteria
                                    .where("created_by").is(memberId)
                                    .or("supervisor_id").is(employeeId);
                            yield fetchWithRoleAndKeyword(roleCriteria, keyword, index, size);
                        }
                        default -> {
                            // member — เห็นเฉพาะของตัวเอง
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

                    // ── 1. Audit member IDs
                    List<Long> auditIds = new ArrayList<>();
                    if (registerRequest.getCreatedBy() != null) auditIds.add(registerRequest.getCreatedBy());
                    if (registerRequest.getUpdatedBy()  != null) auditIds.add(registerRequest.getUpdatedBy());

                    Mono<Map<Long, Member>> auditMono = auditIds.isEmpty()
                            ? Mono.just(new HashMap<>())
                            : commonService.fetchMembersByIds(auditIds);

                    // ── 2. Resolve department name by department code/id stored in registerRequest
                    //    สมมติ department field เก็บ departmentCode → ค้นใน Department table
                    Mono<String> deptNameMono = Mono.justOrEmpty(registerRequest.getDepartment())
                            .flatMap(deptCode ->
                                    template.selectOne(
                                            Query.query(Criteria.where("department_code").is(deptCode)),
                                            Department.class
                                    ).map(Department::getDepartment)   // ชื่อแผนก
                            )
                            .defaultIfEmpty(registerRequest.getDepartment() != null
                                    ? registerRequest.getDepartment() : "-");

                    // ── 3. Resolve responsible / supervisor / manager names
                    //    responsibleId, supervisorId, managerId เก็บ employeeId → ค้นใน Member table
                    Mono<String> responsibleMono = resolveMemberName(registerRequest.getResponsibleId());
                    Mono<String>  supervisorMono = resolveMemberName(registerRequest.getSupervisorId());
                    Mono<String>    managerMono  = resolveMemberName(registerRequest.getManagerId());

                    return Mono.zip(auditMono, deptNameMono, responsibleMono, supervisorMono, managerMono)
                            .map(tuple -> {
                                Map<Long, Member> memberMap  = tuple.getT1();
                                String deptName              = tuple.getT2();
                                String responsibleName       = tuple.getT3();
                                String supervisorName        = tuple.getT4();
                                String managerName           = tuple.getT5();

                                AuditMemberDTO createdByDTO = registerRequest.getCreatedBy() != null
                                        ? AuditMemberDTO.from(memberMap.get(registerRequest.getCreatedBy()))
                                        : null;
                                AuditMemberDTO updatedByDTO = registerRequest.getUpdatedBy() != null
                                        ? AuditMemberDTO.from(memberMap.get(registerRequest.getUpdatedBy()))
                                        : null;

                                RegisterResponseDTO dto = RegisterResponseDTO.from(
                                        registerRequest, createdByDTO, updatedByDTO);

                                // ── ใส่ชื่อที่ resolve ได้
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
                            Query.query(Criteria.where("id").is(id)),
                            Member.class
                    )
                    .map(m -> m.getFirstName() + " " + m.getLastName())
                    .defaultIfEmpty(memberId);
        } catch (NumberFormatException e) {
            return template.selectOne(
                            Query.query(Criteria.where("employee_id").is(memberId)),
                            Member.class
                    )
                    .map(m -> m.getFirstName() + " " + m.getLastName())
                    .defaultIfEmpty(memberId);
        }
    }

    private Flux<RegisterListDTO> convertRegisterListDTOs(List<RegisterRequest> registerRequests) {
        return Flux.fromIterable(registerRequests)
                .map(RegisterListDTO::from);
    }

    public Mono<RegisterDTO> validateData(RegisterDTO registerDTO) {
        if (registerDTO.getDepartment() == null || registerDTO.getDepartment().isEmpty()) {
            return Mono.error(new ThrowException("RG003"));
        }

        Criteria criteria = Criteria.where("department").is(registerDTO.getDepartment());
        Query query = Query.query(criteria);

        return template.select(query, RegisterRequest.class)
                .collectList()
                .flatMap(existingDepartment -> Mono.just(registerDTO));
    }

    public RegisterRequest buildFromDTO(RegisterDTO registerDTO) {
        try {
            // ✅ Convert Maintenance List to JSON
            String maintenanceJson = null;
            if (registerDTO.getMaintenance() != null && !registerDTO.getMaintenance().isEmpty()) {
                maintenanceJson = objectMapper.writeValueAsString(registerDTO.getMaintenance());
            }

            // ✅ Convert Calibration List to JSON
            String calibrationJson = null;
            if (registerDTO.getCalibration() != null && !registerDTO.getCalibration().isEmpty()) {
                calibrationJson = objectMapper.writeValueAsString(registerDTO.getCalibration());
            }

            String attachmentJson = null;
            if (registerDTO.getAttachments() != null && !registerDTO.getAttachments().isEmpty()) {
                attachmentJson = objectMapper.writeValueAsString(registerDTO.getAttachments());
                log.info("✅ Attachments JSON: {}", attachmentJson);
            } else {
                log.warn("⚠️ No attachments to save");
            }

            return RegisterRequest.builder()
                    .id(registerDTO.getId())
                    .department(registerDTO.getDepartment())
                    .machineName(registerDTO.getMachineName())
                    .brand(registerDTO.getBrand())
                    .model(registerDTO.getModel())
                    .serialNumber(registerDTO.getSerialNumber())
                    .price(registerDTO.getPrice())
                    .quantity(registerDTO.getQuantity())
                    .watt(registerDTO.getWatt())
                    .horsePower(registerDTO.getHorsePower())
                    .responsibleId(registerDTO.getResponsibleId())
                    .supervisorId(registerDTO.getSupervisorId())
                    .managerId(registerDTO.getManagerId())
                    .note(registerDTO.getNote())
                    .attachment(attachmentJson)
                    .maintenance(maintenanceJson)
                    .calibration(calibrationJson)
                    .build();

        } catch (Exception e) {
            log.error("❌ Error converting data to JSON", e);
            throw new RuntimeException("Failed to process data: " + e.getMessage());
        }
    }

    private Update buildUpdateFromDTO(RegisterDTO registerDTO) {
        Map<SqlIdentifier, Object> params = new HashMap<>();
        addIfNotNull(params, "department", registerDTO.getDepartment());
        addIfNotNull(params, "machine_name", registerDTO.getMachineName());
        addIfNotNull(params, "brand", registerDTO.getBrand());
        addIfNotNull(params, "model", registerDTO.getModel());
        addIfNotNull(params, "note", registerDTO.getNote());
        addIfNotNull(params, "price", registerDTO.getPrice());
        addIfNotNull(params, "quantity", registerDTO.getQuantity());
        addIfNotNull(params, "watt", registerDTO.getWatt());
        addIfNotNull(params, "housePower", registerDTO.getHorsePower());
        addIfNotNull(params, "manager_id", registerDTO.getManagerId());

        // ✅ Convert attachments to JSON for update
        if (registerDTO.getAttachments() != null) {
            try {
                String attachmentJson = objectMapper.writeValueAsString(registerDTO.getAttachments());
                addIfNotNull(params, "attachment", attachmentJson);
            } catch (Exception e) {
                log.error("❌ Error converting attachments to JSON during update", e);
            }
        }

        return Update.from(params);
    }

//    private Flux<RegisterResponseDTO> convertRegisterListDTOs(List<RegisterRequest> registerRequests) {
//        if (registerRequests.isEmpty()) {
//            return Flux.empty();
//        }
//        List<Long> createdByIds = registerRequests.stream()
//                .map(RegisterRequest::getCreatedBy)
//                .filter(Objects::nonNull)
//                .distinct()
//                .toList();
//        List<Long> updatedByIds = registerRequests.stream()
//                .map(RegisterRequest::getUpdatedBy)
//                .filter(Objects::nonNull)
//                .distinct()
//                .toList();
//        return Mono.zip(
//                        commonService.fetchMembersByIds(createdByIds),
//                        commonService.fetchMembersByIds(updatedByIds)
//                )
//                .flatMapMany(tuple -> {
//                    Map<Long, Member> createdByMap = tuple.getT1();
//                    Map<Long, Member> updatedByMap = tuple.getT2();
//                    return Flux.fromIterable(registerRequests)
//                            .map(registerRequest -> RegisterResponseDTO.from(
//                                    registerRequest,
//                                    AuditMemberDTO.from(createdByMap.get(registerRequest.getCreatedBy())),
//                                    AuditMemberDTO.from(updatedByMap.get(registerRequest.getUpdatedBy()))
//                            ));
//                });
//    }

    private void addIfNotNull(Map<SqlIdentifier, Object> params, String fieldName, Object value) {
        if (value != null) {
            params.put(SqlIdentifier.quoted(fieldName), value);
        }
    }
}