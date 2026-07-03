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
import java.util.stream.Collectors;

@Slf4j
@Service
@AllArgsConstructor
public class RegisterService {

    private final LarkService larkService;
    private final R2dbcEntityTemplate template;
    private final CommonService commonService;
    private final ObjectMapper objectMapper;

    // ─── CREATE ───────────────────────────────────────────────────────────────

    public Mono<ApiResponse<Void>> create(RegisterDTO dto) {
        return validateData(dto)
                .flatMap(validateDTO -> {
                    RegisterRequest registerRequest = buildFromDTO(validateDTO);
                    return commonService.save(registerRequest, RegisterRequest.class)
                            .flatMap(this::sendLarkNotificationToMember)
                            .then(Mono.just(ApiResponse.<Void>success("RG001")));
                })
                .onErrorResume(ThrowException.class, e -> {
                    log.warn("Business validation failed during register create: {}", e.getMessage());
                    return Mono.just(ApiResponse.<Void>error(e.getCode(), e.getMessage()));
                })
                .onErrorResume(e -> {
                    log.error("Failed to create the register: {}", e.getMessage(), e);
                    return Mono.just(ApiResponse.<Void>error("RG002", e.getMessage()));
                });
    }

    // ─── UPDATE ───────────────────────────────────────────────────────────────

    public Mono<ApiResponse<Void>> update(RegisterDTO registerDTO) {
        return validateData(registerDTO)
                .flatMap(validateData -> {
                    Update update = buildUpdateFromDTO(validateData);
                    return commonService.update(registerDTO.getId(), update, RegisterRequest.class)
                            .then(Mono.just(ApiResponse.<Void>success("RG003")));
                })
                .onErrorResume(ThrowException.class, e -> {
                    log.warn("Business validation failed during register update: {}", e.getMessage());
                    return Mono.just(ApiResponse.<Void>error(e.getCode(), e.getMessage()));
                })
                .onErrorResume(e -> {
                    log.error("Failed to update the register: {}", e.getMessage());
                    return Mono.just(ApiResponse.<Void>error("RG004", e.getMessage()));
                });
    }

    // ─── DELETE ───────────────────────────────────────────────────────────────

    public Mono<ApiResponse<Void>> delete(List<Long> ids) {
        return commonService.deleteEntitiesByIds(
                ids,
                RegisterRequest.class,
                "RG005", "RG006", "RG007",
                RegisterRequest::getMachineName,
                names -> Mono.empty()
        );
    }

    // ─── GET WITH ROLE (paged list) ───────────────────────────────────────────

    public Mono<PagedResponse<RegisterListDTO>> getWithRole(String keyword, int index, int size) {
        return ReactiveSecurityContextHolder.getContext()
                .mapNotNull(ctx -> (MemberPrincipal) Objects.requireNonNull(ctx.getAuthentication()).getPrincipal())
                .flatMap(principal -> {
                    String role     = principal.role();
                    Long   memberId = principal.memberId();

                    return switch (role) {
                        case "ADMIN" -> {
                            Criteria criteria = buildKeywordCriteria(keyword);
                            Query    query    = Query.query(criteria)
                                    .with(commonService.pageable(index, size, "created_at"));
                            yield commonService.executePagedQuery(
                                    index, size, query, criteria,
                                    RegisterRequest.class, this::convertRegisterListDTOs);
                        }
                        case "MANAGER" -> {
                            Criteria roleCriteria = Criteria
                                    .where("created_by").is(memberId)
                                    .or("manager_id").is(memberId);
                            yield fetchWithRoleAndKeyword(roleCriteria, keyword, index, size);
                        }
                        case "SUPERVISOR" -> {
                            Criteria roleCriteria = Criteria
                                    .where("created_by").is(memberId)
                                    .or("supervisor_id").is(memberId);
                            yield fetchWithRoleAndKeyword(roleCriteria, keyword, index, size);
                        }
                        default -> {
                            Criteria roleCriteria = Criteria.where("created_by").is(memberId);
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
                    Criteria.where("machine_name").like("%" + keyword + "%").ignoreCase(true));
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

    // ─── convertRegisterListDTOs ──────────────────────────────────────────────

    private Flux<RegisterListDTO> convertRegisterListDTOs(List<RegisterRequest> list) {
        // ── 1. รวบรวม member IDs สำหรับ audit ────────────────────────────────
        Set<Long> memberIdSet = new HashSet<>();
        for (RegisterRequest r : list) {
            if (r.getCreatedBy() != null) memberIdSet.add(r.getCreatedBy());
            if (r.getUpdatedBy() != null) memberIdSet.add(r.getUpdatedBy());
        }

        Mono<Map<Long, Member>> memberMapMono = memberIdSet.isEmpty()
                ? Mono.just(new HashMap<>())
                : commonService.fetchMembersByIds(new ArrayList<>(memberIdSet));

        // ── 2. batch-query นับจำนวน machine ต่อ register ID ─────────────────
        List<Long> registerIds = list.stream().map(RegisterRequest::getId).toList();

        Mono<Map<Long, Long>> machineCountMapMono = registerIds.isEmpty()
                ? Mono.just(Map.of())
                : template.getDatabaseClient()
                .sql("SELECT note FROM machine WHERE note LIKE 'REF:REGISTER-%'")
                .fetch().all()
                .mapNotNull(row -> {
                    Object val = row.get("note");
                    if (val == null) return null;
                    // ตัดบรรทัดแรกออกมา กรณี note มีข้อความต่อท้าย
                    // เช่น "REF:REGISTER-7\nเครื่องมือประจำรถ..."
                    String firstLine = val.toString().split("\\R")[0].trim();
                    if (!firstLine.startsWith("REF:REGISTER-")) return null;
                    try {
                        return Long.parseLong(firstLine.substring("REF:REGISTER-".length()));
                    } catch (NumberFormatException e) {
                        return null;
                    }
                })
                .filter(registerIds::contains)
                .collect(Collectors.groupingBy(id -> id, Collectors.counting()));

        // ── 3. zip แล้ว map ───────────────────────────────────────────────────
        return Mono.zip(memberMapMono, machineCountMapMono)
                .flatMapMany(tuple -> {
                    Map<Long, Member> memberMap       = tuple.getT1();
                    Map<Long, Long>   machineCountMap = tuple.getT2();

                    return Flux.fromIterable(list).map(r -> {
                        RegisterListDTO dto = RegisterListDTO.from(
                                r,
                                memberMap.get(r.getCreatedBy()),
                                memberMap.get(r.getUpdatedBy())
                        );
                        long count = machineCountMap.getOrDefault(r.getId(), 0L);
                        dto.setHasMachine(count > 0);
                        dto.setMachineCount(count);
                        return dto;
                    });
                });
    }

    // ─── GET BY ID ────────────────────────────────────────────────────────────

    public Mono<ApiResponse<RegisterResponseDTO>> getById(Long id) {
        return template.selectOne(Query.query(Criteria.where("id").is(id)), RegisterRequest.class)
                .flatMap(registerRequest -> {
                    List<Long> auditIds = new ArrayList<>();
                    if (registerRequest.getCreatedBy() != null) auditIds.add(registerRequest.getCreatedBy());
                    if (registerRequest.getUpdatedBy() != null) auditIds.add(registerRequest.getUpdatedBy());

                    Mono<Map<Long, Member>> auditMono = auditIds.isEmpty()
                            ? Mono.just(new HashMap<>())
                            : commonService.fetchMembersByIds(auditIds);

                    Mono<String> deptNameMono = Mono.justOrEmpty(registerRequest.getDepartment())
                            .flatMap(deptCode -> template.selectOne(
                                            Query.query(Criteria.where("department_code").is(deptCode)),
                                            Department.class)
                                    .map(Department::getDepartment))
                            .defaultIfEmpty(registerRequest.getDepartment() != null
                                    ? registerRequest.getDepartment() : "-");

                    Mono<String> responsibleMono = resolveMemberName(registerRequest.getResponsibleId());
                    Mono<String> supervisorMono  = resolveMemberName(registerRequest.getSupervisorId());
                    Mono<String> managerMono     = resolveMemberName(registerRequest.getManagerId());

                    return Mono.zip(auditMono, deptNameMono, responsibleMono, supervisorMono, managerMono)
                            .map(tuple -> {
                                Map<Long, Member> memberMap       = tuple.getT1();
                                String            deptName        = tuple.getT2();
                                String            responsibleName = tuple.getT3();
                                String            supervisorName  = tuple.getT4();
                                String            managerName     = tuple.getT5();

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

                                return ApiResponse.success("RG010", dto);
                            });
                })
                .switchIfEmpty(Mono.just(ApiResponse.error("RG011", "Data not found")))
                .onErrorResume(e -> {
                    log.error("Failed to fetch register {}: {}", id, e.getMessage(), e);
                    return Mono.just(ApiResponse.error("RG012", e.getMessage()));
                });
    }

    // ─── LARK NOTIFICATION ────────────────────────────────────────────────────

    private Mono<Void> sendLarkNotificationToMember(RegisterRequest saved) {
        List<Long> targetMemberIds = List.of(1L, 3L, 99L);

        String inClause = targetMemberIds.stream()
                .map(String::valueOf)
                .collect(Collectors.joining(", "));

        return template.getDatabaseClient()
                .sql("SELECT id, first_name, last_name, mobiles FROM member WHERE id IN (" + inClause + ")")
                .fetch().all()
                .collectList()
                .flatMap(rows -> {
                    List<String> rawMobiles = rows.stream()
                            .map(row -> row.get("mobiles"))
                            .filter(v -> v != null && !v.toString().isBlank())
                            .map(Object::toString)
                            .toList();

                    log.info("Raw mobiles for ids={}: {}", targetMemberIds, rawMobiles);

                    if (rawMobiles.isEmpty()) {
                        log.warn("No mobile found for member ids={}", targetMemberIds);
                        return Mono.<Void>empty();
                    }

                    String cardJson = buildRegisterCardJson(saved);

                    return larkService.batchGetOpenIdsByMobile(rawMobiles)
                            .flatMap(openIdMap -> {
                                log.info("openIdMap resolved: {}", openIdMap);

                                List<Mono<Void>> sends = rawMobiles.stream()
                                        .map(mobile -> {
                                            String alt = mobile.startsWith("+66")
                                                    ? "0" + mobile.substring(3)
                                                    : "+66" + mobile.substring(1);

                                            String openId = openIdMap.getOrDefault(mobile,
                                                    openIdMap.get(alt));

                                            if (openId == null) {
                                                log.warn("Cannot resolve open_id for mobile={} alt={}", mobile, alt);
                                                return Mono.<Void>empty();
                                            }

                                            log.info("Sending to openId={}", openId);
                                            return larkService.sendCardMessage(openId, cardJson);
                                        })
                                        .toList();

                                return Mono.when(sends);
                            });
                })
                .onErrorResume(e -> {
                    log.error("Lark notify error: {}", e.getMessage(), e);
                    return Mono.empty();
                });
    }

    private String buildRegisterCardJson(RegisterRequest req) {
        String machineName = req.getMachineName() != null ? req.getMachineName() : "-";
        String department  = req.getDepartment()  != null ? req.getDepartment()  : "-";
        String serialNo    = req.getSerialNumber() != null ? req.getSerialNumber() : "-";

        return """
                {
                  "config": { "wide_screen_mode": true },
                  "header": {
                    "title": {
                      "tag": "plain_text",
                      "content": "🔔 มีการลงทะเบียนเครื่องจักรใหม่"
                    },
                    "template": "blue"
                  },
                  "elements": [
                    {
                      "tag": "div",
                      "fields": [
                        {
                          "is_short": true,
                          "text": { "tag": "lark_md", "content": "**ชื่อเครื่องจักร**\\n%s" }
                        },
                        {
                          "is_short": true,
                          "text": { "tag": "lark_md", "content": "**แผนก**\\n%s" }
                        }
                      ]
                    },
                    {
                      "tag": "div",
                      "fields": [
                        {
                          "is_short": true,
                          "text": { "tag": "lark_md", "content": "**Serial No**\\n%s" }
                        }
                      ]
                    }
                  ]
                }
                """.formatted(machineName, department, serialNo);
    }

    // ─── VALIDATE ─────────────────────────────────────────────────────────────

    public Mono<RegisterDTO> validateData(RegisterDTO registerDTO) {
        if (registerDTO.getDepartment() == null || registerDTO.getDepartment().isEmpty()) {
            return Mono.error(new ThrowException("RG020", "Department is required"));
        }
        return Mono.just(registerDTO);
    }

    // ─── BUILD FROM DTO ───────────────────────────────────────────────────────

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

            String attachmentJson = null;
            if (dto.getAttachments() != null && !dto.getAttachments().isEmpty()) {
                attachmentJson = objectMapper.writeValueAsString(dto.getAttachments());
                log.info("Attachments JSON: {}", attachmentJson);
            }

            String workInstructionJson = null;
            if (dto.getWorkInstructions() != null && !dto.getWorkInstructions().isEmpty()) {
                workInstructionJson = objectMapper.writeValueAsString(dto.getWorkInstructions());
                log.info("WorkInstruction JSON: {}", workInstructionJson);
            }

            String warrantyFilesJson = null;
            if ("YES".equals(dto.getHasWarranty())
                    && dto.getWarrantyFiles() != null
                    && !dto.getWarrantyFiles().isEmpty()) {
                warrantyFilesJson = objectMapper.writeValueAsString(dto.getWarrantyFiles());
                log.info("WarrantyFiles JSON: {}", warrantyFilesJson);
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
                    .hasWarranty(dto.getHasWarranty())
                    .warrantyNote("YES".equals(dto.getHasWarranty()) ? dto.getWarrantyNote() : null)
                    .warrantyExpireDate("YES".equals(dto.getHasWarranty()) ? dto.getWarrantyExpireDate() : null)
                    .warrantyFiles(warrantyFilesJson)
                    .build();
        } catch (Exception e) {
            throw new RuntimeException("Failed to process data: " + e.getMessage());
        }
    }

    // ─── BUILD UPDATE ─────────────────────────────────────────────────────────

    private Update buildUpdateFromDTO(RegisterDTO dto) {
        Map<SqlIdentifier, Object> params = new HashMap<>();
        addIfNotNull(params, "department",     dto.getDepartment());
        addIfNotNull(params, "machine_name",   dto.getMachineName());
        addIfNotNull(params, "brand",          dto.getBrand());
        addIfNotNull(params, "model",          dto.getModel());
        addIfNotNull(params, "note",           dto.getNote());
        addIfNotNull(params, "price",          dto.getPrice());
        addIfNotNull(params, "quantity",       dto.getQuantity());
        addIfNotNull(params, "watt",           dto.getWatt());
        addIfNotNull(params, "horse_power",    dto.getHorsePower());
        addIfNotNull(params, "responsible_id", dto.getResponsibleId());
        addIfNotNull(params, "supervisor_id",  dto.getSupervisorId());
        addIfNotNull(params, "manager_id",     dto.getManagerId());

        if (dto.getAttachments() != null) {
            try {
                addIfNotNull(params, "attachment",
                        objectMapper.writeValueAsString(dto.getAttachments()));
            } catch (Exception e) {
                log.error("Error converting attachments to JSON", e);
            }
        }

        if (dto.getWorkInstructions() != null) {
            try {
                params.put(SqlIdentifier.quoted("work_instruction"),
                        dto.getWorkInstructions().isEmpty()
                                ? null
                                : objectMapper.writeValueAsString(dto.getWorkInstructions()));
            } catch (Exception e) {
                log.error("Error converting workInstructions to JSON", e);
            }
        }

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
                log.error("Error converting warrantyFiles to JSON", e);
            }
        }

        return Update.from(params);
    }

    // ─── HELPERS ──────────────────────────────────────────────────────────────

    private Mono<String> resolveMemberName(Long memberId) {
        if (memberId == null) return Mono.just("-");
        return template.selectOne(
                        Query.query(Criteria.where("id").is(memberId)), Member.class)
                .map(m -> m.getFirstName() + " " + m.getLastName())
                .defaultIfEmpty(String.valueOf(memberId));
    }

    private void addIfNotNull(Map<SqlIdentifier, Object> params, String fieldName, Object value) {
        if (value != null) params.put(SqlIdentifier.quoted(fieldName), value);
    }
}