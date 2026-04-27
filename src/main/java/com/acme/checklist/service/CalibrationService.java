package com.acme.checklist.service;

import com.acme.checklist.entity.*;
import com.acme.checklist.exception.ThrowException;
import com.acme.checklist.payload.ApiResponse;
import com.acme.checklist.payload.MemberPrincipal;
import com.acme.checklist.payload.PagedResponse;
import com.acme.checklist.payload.calibration.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class CalibrationService {

    private final R2dbcEntityTemplate template;
    private final CommonService commonService;

    // ─── UPDATE ───────────────────────────────────────────────────────────────

    public Mono<ApiResponse<Void>> update(CalibrationDTO dto) {
        return validateData(dto, true)
                .flatMap(v -> commonService.update(dto.getId(), buildUpdateFromDTO(v), CalibrationRecord.class)
                        .then(Mono.just(ApiResponse.success("MS003"))))
                .onErrorResume(e -> {
                    log.error("Failed to update the calibration: {}", e.getMessage());
                    return Mono.just(ApiResponse.error("MS004", e.getMessage()));
                });
    }

    // ─── GET WITH ROLE ────────────────────────────────────────────────────────

    public Mono<PagedResponse<CalibrationListDTO>> getWithRole(String keyword, int index, int size) {
        return ReactiveSecurityContextHolder.getContext()
                .map(ctx -> (MemberPrincipal) ctx.getAuthentication().getPrincipal())
                .flatMap(principal -> {
                    String role       = principal.role();
                    String employeeId = principal.employeeId();
                    Long   memberId   = principal.memberId();

                    return switch (role) {
                        case "ADMIN" -> {
                            Criteria criteria = buildKeywordCriteria(keyword);
                            Query query = Query.query(criteria).with(commonService.pageable(index, size, "created_at"));
                            yield commonService.executePagedQuery(index, size, query, criteria,
                                    CalibrationRecord.class, this::convertCalibrationListDTOs);
                        }
                        case "MANAGER"    -> fetchByMachineRole(employeeId, "manager_id",            memberId, keyword, index, size);
                        case "SUPERVISOR" -> fetchByMachineRole(employeeId, "supervisor_id",         memberId, keyword, index, size);
                        default           -> fetchByMachineRole(employeeId, "responsible_person_id", null,     keyword, index, size);
                    };
                });
    }

    private Mono<PagedResponse<CalibrationListDTO>> fetchByMachineRole(
            String employeeId, String roleColumn, Long memberId, String keyword, int index, int size) {

        return template.select(
                        Query.query(Criteria.where(roleColumn).is(employeeId)),
                        Machine.class)
                .map(Machine::getMachineCode)
                .collectList()
                .flatMap(machineCodes -> {
                    if (machineCodes.isEmpty() && memberId == null) {
                        return Mono.just(PagedResponse.<CalibrationListDTO>builder()
                                .success(true).message("Success").data(List.of())
                                .totalElements(0L).totalPages(0).index(index).size(size).build());
                    }

                    Criteria criteria;

                    if (machineCodes.isEmpty()) {
                        // ไม่มี machine แต่อาจมี record ที่ manager/supervisor ตรง
                        criteria = Criteria.where("manager").is(String.valueOf(memberId));
                    } else if (memberId != null) {
                        // MANAGER/SUPERVISOR — เห็น machine ที่รับผิดชอบ + record ที่ตัวเองเป็น manager/supervisor
                        criteria = Criteria.where("machine_code").in(machineCodes)
                                .or("manager").is(String.valueOf(memberId));
                    } else {
                        // MEMBER — เห็นเฉพาะ machine ที่รับผิดชอบ
                        criteria = Criteria.where("machine_code").in(machineCodes);
                    }

                    if (StringUtils.hasText(keyword)) {
                        criteria = criteria.and(
                                Criteria.where("machine_code").like("%" + keyword + "%").ignoreCase(true));
                    }

                    Query query = Query.query(criteria).with(commonService.pageable(index, size, "created_at"));
                    return commonService.executePagedQuery(index, size, query, criteria,
                            CalibrationRecord.class, this::convertCalibrationListDTOs);
                });
    }

    // ─── DEPARTMENT SUMMARY WITH ROLE ─────────────────────────────────────────

    public Flux<CalibrationDepartmentSummaryDTO> getDepartmentSummaryWithRole() {
        return ReactiveSecurityContextHolder.getContext()
                .map(ctx -> (MemberPrincipal) ctx.getAuthentication().getPrincipal())
                .flatMapMany(principal -> {
                    String role       = principal.role();
                    String employeeId = principal.employeeId();

                    String roleFilter = switch (role) {
                        case "ADMIN"      -> "";
                        case "MANAGER"    -> "AND (m.responsible_person_id = '" + employeeId + "' OR m.manager_id = '" + employeeId + "')";
                        case "SUPERVISOR" -> "AND (m.responsible_person_id = '" + employeeId + "' OR m.supervisor_id = '" + employeeId + "')";
                        default           -> "AND m.responsible_person_id = '" + employeeId + "'";
                    };

                    String sql = """
                        SELECT
                            COALESCE(m.department, SUBSTRING(c.machine_code FROM 1 FOR 4)) as department,
                            CASE
                                WHEN d.department IS NOT NULL AND d.division IS NOT NULL AND d.division != '' THEN
                                    d.department || ' - ' || d.division
                                WHEN d.department IS NOT NULL THEN
                                    d.department
                                ELSE
                                    'Department ' || COALESCE(m.department, SUBSTRING(c.machine_code FROM 1 FOR 4))
                            END as department_name,
                            COUNT(*) as total,
                            COUNT(CASE WHEN c.results = 'Pass' THEN 1 END) as total_pass,
                            COUNT(CASE WHEN c.results = 'Not Pass' THEN 1 END) as total_not_pass,
                            COUNT(CASE WHEN c.calibration_status = 'On Time' THEN 1 END) as total_on_time,
                            COUNT(CASE WHEN c.calibration_status = 'Overdue' THEN 1 END) as total_overdue,
                            COUNT(CASE WHEN c.certificate_date IS NOT NULL THEN 1 END) as total_completed,
                            COUNT(CASE WHEN c.certificate_date IS NULL THEN 1 END) as total_pending
                        FROM calibration_record c
                        LEFT JOIN machine m ON c.machine_code = m.machine_code
                        LEFT JOIN department d ON m.department = d.department_code
                        WHERE 1=1
                          %s
                        GROUP BY
                            COALESCE(m.department, SUBSTRING(c.machine_code FROM 1 FOR 4)),
                            d.department, d.division
                        HAVING COUNT(*) > 0
                        ORDER BY total DESC
                        """.formatted(roleFilter);

                    return template.getDatabaseClient()
                            .sql(sql)
                            .map((row, metadata) -> mapDepartmentSummary(row))
                            .all()
                            .onErrorResume(e -> {
                                log.error("Error fetching calibration department summary with role", e);
                                return Flux.empty();
                            });
                });
    }

    // ─── GET BY ID ────────────────────────────────────────────────────────────

    public Mono<ApiResponse<CalibrationResponseDTO>> getById(Long id) {
        return template.selectOne(Query.query(Criteria.where("id").is(id)), CalibrationRecord.class)
                .flatMap(cal -> {
                    List<Long> memberIds = new ArrayList<>();
                    if (cal.getCreatedBy() != null) memberIds.add(cal.getCreatedBy());
                    if (cal.getUpdatedBy() != null) memberIds.add(cal.getUpdatedBy());
                    Mono<Map<Long, Member>> membersMono = memberIds.isEmpty()
                            ? Mono.just(new HashMap<>()) : commonService.fetchMembersByIds(memberIds);
                    return membersMono.map(memberMap ->
                            ApiResponse.success("MS017", CalibrationResponseDTO.from(cal)));
                })
                .switchIfEmpty(Mono.just(ApiResponse.error("MS018", "Calibration not found")))
                .onErrorResume(e -> {
                    log.error("Failed to fetch calibration: {}", e.getMessage(), e);
                    return Mono.just(ApiResponse.error("MS019", e.getMessage()));
                });
    }

    // ─── GET BY MACHINE CODE ──────────────────────────────────────────────────

    public Mono<ApiResponse<List<CalibrationResponseDTO>>> getByMachineCode(String machineCode) {
        return template.select(
                        Query.query(Criteria.where("machine_code").is(machineCode))
                                .sort(Sort.by("due_date").ascending()),
                        CalibrationRecord.class)
                .collectList()
                .flatMap(records -> {
                    if (records.isEmpty()) {
                        return Mono.just(ApiResponse.<List<CalibrationResponseDTO>>error("MS018", "Data not found"));
                    }
                    return Mono.just(ApiResponse.success("MS017",
                            records.stream().map(CalibrationResponseDTO::from).toList()));
                })
                .onErrorResume(e -> {
                    log.error("Failed to fetch calibration by machine code: {}", e.getMessage(), e);
                    return Mono.just(ApiResponse.error("MS019", e.getMessage()));
                });
    }

    // ─── VALIDATE ─────────────────────────────────────────────────────────────

    public Mono<CalibrationDTO> validateData(CalibrationDTO dto, boolean isUpdate) {
        if (dto.getDueDate() == null) {
            return Mono.error(new ThrowException("MS008", "Calibration due date is required"));
        }
        return template.select(Query.query(Criteria.where("id").is(dto.getId())), CalibrationRecord.class)
                .collectList()
                .flatMap(existing -> Mono.just(dto));
    }

    // ─── HELPERS ──────────────────────────────────────────────────────────────

    private Criteria buildKeywordCriteria(String keyword) {
        if (StringUtils.hasText(keyword)) {
            return Criteria.where("machine_code").like("%" + keyword + "%").ignoreCase(true);
        }
        return Criteria.empty();
    }

    private Update buildUpdateFromDTO(CalibrationDTO dto) {
        Map<SqlIdentifier, Object> p = new HashMap<>();
        addIfNotNull(p, "dueDate",              dto.getDueDate());
        addIfNotNull(p, "startDate",            dto.getStartDate());
        addIfNotNull(p, "certificateDate",      dto.getCertificateDate());
        addIfNotNull(p, "results",              dto.getResults());
        addIfNotNull(p, "criteria",             dto.getCriteria());
        addIfNotNull(p, "measuringRange",       dto.getMeasuringRange());
        addIfNotNull(p, "accuracy",             dto.getAccuracy());
        addIfNotNull(p, "calibrationRange",     dto.getCalibrationRange());
        addIfNotNull(p, "calibrationStatus",    dto.getCalibrationStatus());
        addIfNotNull(p, "attachment",           dto.getAttachment());
        addIfNotNull(p, "note",                 dto.getNote());
        addIfNotNull(p, "permissibleCapacity",  dto.getPermissibleCapacity());
        addIfNotNull(p, "comment",              dto.getComment());
        addIfNotNull(p, "resolution",           dto.getResolution());
        addIfNotNull(p, "maxUncertainty",       dto.getMaxUncertainty());
        addIfNotNull(p, "mpe",                  dto.getMpe());
        addIfNotNull(p, "checkMpe",             dto.getCheckMpe());
        addIfNotNull(p, "checkResolution",      dto.getCheckResolution());
        addIfNotNull(p, "checkResult",          dto.getCheckResult());
        addIfNotNull(p, "reasonNotPass",        dto.getReasonNotPass());
        return Update.from(p);
    }

    private Flux<CalibrationListDTO> convertCalibrationListDTOs(List<CalibrationRecord> records) {
        return Flux.fromIterable(records).map(CalibrationListDTO::from);
    }

    private CalibrationDepartmentSummaryDTO mapDepartmentSummary(io.r2dbc.spi.Row row) {
        try {
            return CalibrationDepartmentSummaryDTO.builder()
                    .department(row.get("department", String.class))
                    .departmentName(row.get("department_name", String.class))
                    .total(getLongValue(row, "total"))
                    .totalPass(getLongValue(row, "total_pass"))
                    .totalNotPass(getLongValue(row, "total_not_pass"))
                    .totalOnTime(getLongValue(row, "total_on_time"))
                    .totalOverdue(getLongValue(row, "total_overdue"))
                    .totalCompleted(getLongValue(row, "total_completed"))
                    .totalPending(getLongValue(row, "total_pending"))
                    .build();
        } catch (Exception e) {
            log.error("Error mapping calibration department summary row", e);
            throw new RuntimeException("Error mapping calibration department summary data", e);
        }
    }

    private Long getLongValue(io.r2dbc.spi.Row row, String columnName) {
        Object v = row.get(columnName);
        if (v == null) return 0L;
        if (v instanceof Long l) return l;
        if (v instanceof Number n) return n.longValue();
        return 0L;
    }

    private void addIfNotNull(Map<SqlIdentifier, Object> params, String fieldName, Object value) {
        if (value != null) params.put(SqlIdentifier.quoted(fieldName), value);
    }
}