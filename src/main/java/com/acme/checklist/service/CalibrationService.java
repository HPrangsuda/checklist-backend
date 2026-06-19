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
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class CalibrationService {

    private final R2dbcEntityTemplate template;
    private final CommonService commonService;

    // ─── UPDATE ───────────────────────────────────────────────────────────────

    public Mono<ApiResponse<Void>> update(CalibrationDTO dto) {
        return validateData(dto)
                .flatMap(v -> {
                    DatabaseClient.GenericExecuteSpec spec = buildUpdateSpec(v);
                    if (spec == null) {
                        return Mono.just(ApiResponse.<Void>success("MS003"));
                    }
                    return spec.then()
                            .then(Mono.just(ApiResponse.<Void>success("MS003")));
                })
                .onErrorResume(e -> {
                    return Mono.just(ApiResponse.error("MS004", e.getMessage()));
                });
    }

    // ─── getPage (SQL-injection safe, JOIN machine + department) ─────────────

    public Mono<PagedResponse<CalibrationResponseDTO>> getPage(String keyword, int index, int size) {
        return ReactiveSecurityContextHolder.getContext()
                .mapNotNull(ctx -> (MemberPrincipal) Objects.requireNonNull(ctx.getAuthentication()).getPrincipal())
                .flatMap(principal -> {
                    String role     = principal.role();
                    Long   memberId = principal.memberId();
                    boolean hasKw   = StringUtils.hasText(keyword);

                    String roleFragment = switch (role) {
                        case "ADMIN"      -> "";
                        case "MANAGER"    -> "AND m.manager_id    = :memberId";
                        case "SUPERVISOR" -> "AND m.supervisor_id = :memberId";
                        default           -> "AND m.responsible_person_id = :memberId";
                    };

                    String kwFragment = hasKw
                            ? "AND (c.machine_code ILIKE :kw OR c.machine_name ILIKE :kw)"
                            : "";

                    String where = "WHERE 1=1 " + roleFragment + " " + kwFragment;

                    String countSql = """
                            SELECT COUNT(*)
                            FROM calibration_record c
                            LEFT JOIN machine m ON m.machine_code = c.machine_code
                            """ + where;

                    String dataSql = """
                            SELECT
                                c.id,
                                c.machine_code,
                                c.machine_name,
                                c.years,
                                c.due_date,
                                c.start_date,
                                c.certificate_date,
                                c.results,
                                c.criteria,
                                c.measuring_range,
                                c.accuracy,
                                c.calibration_range,
                                c.calibration_status,
                                c.attachment,
                                c.note,
                                c.permissible_capacity,
                                c.comment,
                                c.resolution,
                                c.max_uncertainty,
                                c.mpe,
                                c.check_mpe,
                                c.check_resolution,
                                c.check_result,
                                c.reason_not_pass,
                                m.responsible_person_name AS responsible_maintenance_name,
                                m.department              AS machine_department_code,
                                d.department              AS machine_department_name
                            FROM calibration_record c
                            LEFT JOIN machine m ON m.machine_code = c.machine_code
                            LEFT JOIN department d ON d.department_code = m.department
                            """ + where + """

                            ORDER BY c.due_date ASC NULLS LAST
                            LIMIT :size OFFSET :offset
                            """;

                    String kwValue = hasKw ? "%" + keyword.trim() + "%" : null;

                    DatabaseClient.GenericExecuteSpec countSpec = template.getDatabaseClient().sql(countSql);
                    DatabaseClient.GenericExecuteSpec dataSpec  = template.getDatabaseClient().sql(dataSql);

                    if (!"ADMIN".equals(role)) {
                        countSpec = countSpec.bind("memberId", memberId);
                        dataSpec  = dataSpec.bind("memberId", memberId);
                    }
                    if (hasKw) {
                        countSpec = countSpec.bind("kw", kwValue);
                        dataSpec  = dataSpec.bind("kw", kwValue);
                    }

                    dataSpec = dataSpec
                            .bind("size",   size)
                            .bind("offset", (long) index * size);

                    Mono<Long> countMono = countSpec
                            .map((row, meta) -> {
                                Object v = row.get(0);
                                return v instanceof Number n ? n.longValue() : 0L;
                            })
                            .one()
                            .defaultIfEmpty(0L);

                    Flux<CalibrationResponseDTO> dataFlux = dataSpec
                            .map((row, meta) -> CalibrationResponseDTO.builder()
                                    .id(row.get("id", Long.class))
                                    .machineCode(row.get("machine_code", String.class))
                                    .machineName(row.get("machine_name", String.class))
                                    .years(row.get("years", String.class))
                                    .dueDate(row.get("due_date", LocalDate.class))
                                    .startDate(row.get("start_date", LocalDate.class))
                                    .certificateDate(row.get("certificate_date", LocalDate.class))
                                    .results(row.get("results", String.class))
                                    .criteria(row.get("criteria", String.class))
                                    .measuringRange(row.get("measuring_range", String.class))
                                    .accuracy(row.get("accuracy", String.class))
                                    .calibrationRange(row.get("calibration_range", String.class))
                                    .calibrationStatus(row.get("calibration_status", String.class))
                                    .attachment(row.get("attachment", String.class))
                                    .note(row.get("note", String.class))
                                    .permissibleCapacity(row.get("permissible_capacity", String.class))
                                    .comment(row.get("comment", String.class))
                                    .resolution(row.get("resolution", String.class))
                                    .maxUncertainty(row.get("max_uncertainty", String.class))
                                    .mpe(row.get("mpe", String.class))
                                    .checkMpe(row.get("check_mpe", String.class))
                                    .checkResolution(row.get("check_resolution", String.class))
                                    .checkResult(row.get("check_result", String.class))
                                    .reasonNotPass(row.get("reason_not_pass", String.class))
                                    .responsibleMaintenanceName(row.get("responsible_maintenance_name", String.class))
                                    .machineDepartmentCode(row.get("machine_department_code", String.class))
                                    .machineDepartmentName(row.get("machine_department_name", String.class))
                                    .build())
                            .all();

                    return Mono.zip(countMono, dataFlux.collectList())
                            .map(tuple -> {
                                long total      = tuple.getT1();
                                int  totalPages = (int) Math.ceil((double) total / size);
                                return PagedResponse.<CalibrationResponseDTO>builder()
                                        .success(true)
                                        .message("Success")
                                        .data(tuple.getT2())
                                        .totalElements(total)
                                        .totalPages(totalPages)
                                        .index(index)
                                        .size(size)
                                        .build();
                            });
                })
                .onErrorResume(e -> {
                    log.error("Failed to fetch calibration page: {}", e.getMessage(), e);
                    return Mono.just(PagedResponse.<CalibrationResponseDTO>builder()
                            .success(false)
                            .message(e.getMessage())
                            .data(List.of())
                            .totalElements(0L)
                            .totalPages(0)
                            .index(index)
                            .size(size)
                            .build());
                });
    }

    // ─── DEPARTMENT SUMMARY WITH ROLE ─────────────────────────────────────────

    public Flux<CalibrationDepartmentSummaryDTO> getDepartmentSummaryWithRole() {
        return ReactiveSecurityContextHolder.getContext()
                .mapNotNull(ctx -> (MemberPrincipal) Objects.requireNonNull(ctx.getAuthentication()).getPrincipal())
                .flatMapMany(principal -> {
                    String roleFilter = getString(principal);

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
                            COUNT(CASE WHEN c.calibration_status = 'ON TIME' THEN 1 END) as total_on_time,
                            COUNT(CASE WHEN c.calibration_status = 'OVERDUE' THEN 1 END) as total_overdue,
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

    private static String getString(MemberPrincipal principal) {
        String role       = principal.role();
        String employeeId = principal.employeeId();
        Long   memberId   = principal.memberId();

        return switch (role) {
            case "ADMIN"      -> "";
            case "MANAGER"    -> "AND m.manager_id = " + memberId;
            case "SUPERVISOR" -> "AND m.supervisor_id = " + memberId;
            default           -> "AND m.responsible_person_id = '" + employeeId + "'";
        };
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

    public Mono<CalibrationDTO> validateData(CalibrationDTO dto) {
        return template.selectOne(
                        Query.query(Criteria.where("id").is(dto.getId())),
                        CalibrationRecord.class)
                .switchIfEmpty(Mono.error(new ThrowException("MS018", "Calibration not found")))
                .flatMap(existing -> Mono.just(dto));
    }

    // ─── HELPERS ──────────────────────────────────────────────────────────────

    private DatabaseClient.GenericExecuteSpec buildUpdateSpec(CalibrationDTO dto) {
        List<String> sets   = new ArrayList<>();
        List<Object> values = new ArrayList<>();

        addDateParam(sets, values, "due_date",         dto.getDueDate());
        addDateParam(sets, values, "start_date",       dto.getStartDate());
        addDateParam(sets, values, "certificate_date", dto.getCertificateDate());

        // String fields ปกติ
        addParam(sets, values, "results",              dto.getResults());
        addParam(sets, values, "criteria",             dto.getCriteria());
        addParam(sets, values, "measuring_range",      dto.getMeasuringRange());
        addParam(sets, values, "accuracy",             dto.getAccuracy());
        addParam(sets, values, "calibration_range",    dto.getCalibrationRange());
        addParam(sets, values, "calibration_status",   dto.getCalibrationStatus());
        addParam(sets, values, "attachment",           dto.getAttachment());
        addParam(sets, values, "note",                 dto.getNote());
        addParam(sets, values, "permissible_capacity", dto.getPermissibleCapacity());
        addParam(sets, values, "comment",              dto.getComment());
        addParam(sets, values, "resolution",           dto.getResolution());
        addParam(sets, values, "max_uncertainty",      dto.getMaxUncertainty());
        addParam(sets, values, "mpe",                  dto.getMpe());
        addParam(sets, values, "check_mpe",            dto.getCheckMpe());
        addParam(sets, values, "check_resolution",     dto.getCheckResolution());
        addParam(sets, values, "check_result",         dto.getCheckResult());
        addParam(sets, values, "reason_not_pass",      dto.getReasonNotPass());

        if (sets.isEmpty()) return null;

        values.add(dto.getId());
        String sql = "UPDATE calibration_record SET "
                + String.join(", ", sets)
                + " WHERE id = $" + values.size();

        DatabaseClient.GenericExecuteSpec spec = template.getDatabaseClient().sql(sql);
        for (int i = 0; i < values.size(); i++) {
            spec = spec.bind(i, values.get(i));
        }
        return spec;
    }

    private void addDateParam(List<String> sets, List<Object> values, String column, LocalDate value) {
        if (value != null) {
            values.add(value);
            sets.add(column + " = $" + values.size());
        }
    }

    private void addParam(List<String> sets, List<Object> values, String column, Object value) {
        if (value != null) {
            values.add(value);
            sets.add(column + " = $" + values.size());
        }
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
        return switch (v) {
            case Long l -> l;
            case Number n -> n.longValue();
            case null, default -> 0L;
        };
    }
}