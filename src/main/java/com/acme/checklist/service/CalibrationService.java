package com.acme.checklist.service;

import com.acme.checklist.entity.CalibrationRecord;
import com.acme.checklist.exception.ThrowException;
import com.acme.checklist.payload.ApiResponse;
import com.acme.checklist.payload.MemberPrincipal;
import com.acme.checklist.payload.PagedResponse;
import com.acme.checklist.payload.calibration.*;
import io.r2dbc.spi.Row;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.data.relational.core.query.Criteria;
import org.springframework.data.relational.core.query.Query;
import org.springframework.r2dbc.core.DatabaseClient.GenericExecuteSpec;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.UnaryOperator;

@Slf4j
@Service
@RequiredArgsConstructor
public class CalibrationService {

    private final R2dbcEntityTemplate template;

    // ═══════════════════════════════════════════════════════════════════════════
    // SHARED SQL PIECES
    // ═══════════════════════════════════════════════════════════════════════════

    private static final String NOT_CANCELED = "(c.is_canceled = FALSE OR c.is_canceled IS NULL)";

    private static final String ACTIVE_MACHINE = "m.machine_status IN ('OPERATIONAL', 'UNDER MAINTENANCE')";

    private static final String DEPARTMENT_JOIN =
            "LEFT JOIN LATERAL (\n"
                    + "    SELECT dd.department, dd.division\n"
                    + "    FROM department dd\n"
                    + "    WHERE dd.department_code::text = m.department\n"
                    + "    ORDER BY dd.id\n"
                    + "    LIMIT 1\n"
                    + ") d ON TRUE\n";

    private static final String IS_ON_TIME =
            "c.certificate_date IS NOT NULL AND c.certificate_date <= c.due_date";
    private static final String IS_OVERDUE =
            "(c.certificate_date IS NOT NULL AND c.certificate_date > c.due_date)"
                    + " OR (c.certificate_date IS NULL AND c.due_date < CURRENT_DATE)";

    private static final String MEMBER_NAME_EXPR =
            "COALESCE(NULLIF(TRIM(mb.first_name || ' ' || mb.last_name), ''), mb.first_name, mb.user_name, 'Unassigned')";

    // ═══════════════════════════════════════════════════════════════════════════
    // ROLE FILTER  (การมองเห็นผูกกับเครื่อง — ใช้กับ query ที่ join machine m แล้ว)
    // ═══════════════════════════════════════════════════════════════════════════

    private static String deptPrefixSubquery(Long departmentId) {
        return "(SELECT LEFT(dp.department_code::text, LENGTH(dp.department_code::text) - 1) || '%'"
                + " FROM department dp WHERE dp.id = " + departmentId + ")";
    }

    private static String roleFilter(MemberPrincipal p) {
        if ("ADMIN".equals(p.role())) return "";
        if ("DEPARTMENT_ADMIN".equals(p.role())) {
            return p.departmentId() != null
                    ? "\nAND m.department LIKE " + deptPrefixSubquery(p.departmentId())
                    : "\nAND 1=0";
        }
        Long id = p.memberId();
        if (id == null) return "\nAND 1=0";
        return switch (p.role()) {
            case "MANAGER"    -> "\nAND m.manager_id = "            + id;
            case "SUPERVISOR" -> "\nAND m.supervisor_id = "         + id;
            default           -> "\nAND m.responsible_person_id = " + id;
        };
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // SECURITY HELPERS
    // ═══════════════════════════════════════════════════════════════════════════

    private static Mono<MemberPrincipal> currentPrincipal() {
        return ReactiveSecurityContextHolder.getContext()
                .mapNotNull(ctx -> {
                    Authentication auth = ctx.getAuthentication();
                    return auth != null && auth.getPrincipal() instanceof MemberPrincipal mp ? mp : null;
                });
    }

    private Mono<Boolean> exists(String sql, String param, Object value) {
        return template.getDatabaseClient().sql(sql)
                .bind(param, value)
                .map((row, meta) -> row.get(0) instanceof Number n ? n.longValue() : 0L)
                .one()
                .map(c -> c > 0)
                .defaultIfEmpty(false);
    }

    /**
     * สิทธิ์แก้ไข:
     *   ADMIN            → ทุก record
     *   DEPARTMENT_ADMIN → เครื่องในแผนกตัวเอง หรือเครื่องที่ตัวเองเป็น responsible_person
     *   role อื่น         → เฉพาะเครื่องที่ตัวเองเป็น responsible_person
     */
    private Mono<Boolean> canEdit(MemberPrincipal p, Long recordId) {
        if ("ADMIN".equals(p.role())) return Mono.just(true);
        if (p.memberId() == null)     return Mono.just(false);

        String cond = "m.responsible_person_id = " + p.memberId();
        if ("DEPARTMENT_ADMIN".equals(p.role()) && p.departmentId() != null) {
            cond = "(" + cond + " OR m.department LIKE " + deptPrefixSubquery(p.departmentId()) + ")";
        }

        String sql = "SELECT COUNT(*)\n"
                + "FROM calibration_record c\n"
                + "JOIN machine m ON m.machine_code = c.machine_code\n"
                + "WHERE c.id = :id AND " + cond;
        return exists(sql, "id", recordId);
    }

    /** สิทธิ์ดู record เดียว — ใช้ role filter ชุดเดียวกับหน้า list (ไม่บังคับ machine_status) */
    private Mono<Boolean> canViewRecord(MemberPrincipal p, Long recordId) {
        if ("ADMIN".equals(p.role())) return Mono.just(true);
        String sql = "SELECT COUNT(*)\n"
                + "FROM calibration_record c\n"
                + "JOIN machine m ON m.machine_code = c.machine_code\n"
                + "WHERE c.id = :id"
                + roleFilter(p);
        return exists(sql, "id", recordId);
    }

    /** สิทธิ์ดูประวัติของเครื่อง */
    private Mono<Boolean> canViewMachine(MemberPrincipal p, String machineCode) {
        if ("ADMIN".equals(p.role())) return Mono.just(true);
        String sql = "SELECT COUNT(*)\n"
                + "FROM machine m\n"
                + "WHERE m.machine_code = :machineCode"
                + roleFilter(p);
        return exists(sql, "machineCode", machineCode);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // UPDATE
    // ═══════════════════════════════════════════════════════════════════════════

    public Mono<ApiResponse<Void>> update(CalibrationDTO dto) {
        return currentPrincipal()
                .switchIfEmpty(Mono.error(new ThrowException("MS401", "Unauthenticated")))
                .flatMap(p -> validateData(dto)
                        .flatMap(v -> canEdit(p, v.getId())
                                .flatMap(allowed -> allowed
                                        ? Mono.just(v)
                                        : Mono.error(new ThrowException("MS403", "No permission to edit this calibration record"))))
                        .flatMap(v -> {
                            GenericExecuteSpec spec = buildUpdateSpec(v, p.memberId());
                            if (spec == null) return Mono.just(ApiResponse.<Void>success("MS003"));
                            return spec.then().then(Mono.just(ApiResponse.<Void>success("MS003")));
                        }))
                .onErrorResume(e -> {
                    log.error("Failed to update calibration: {}", e.getMessage());
                    return Mono.just(ApiResponse.error("MS004", e.getMessage()));
                });
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // GET PAGE
    // ═══════════════════════════════════════════════════════════════════════════

    public Mono<PagedResponse<CalibrationResponseDTO>> getPage(
            String keyword, Integer year, String department,
            String results, String calibrationStatus,
            int index, int size) {

        return currentPrincipal()
                .flatMap(principal -> {
                    int     yr      = (year != null) ? year : LocalDate.now().getYear();
                    boolean hasKw   = StringUtils.hasText(keyword);
                    boolean hasDept = StringUtils.hasText(department);
                    boolean hasRes  = StringUtils.hasText(results);
                    boolean hasCal  = StringUtils.hasText(calibrationStatus);

                    String where = "\nWHERE " + NOT_CANCELED
                            + "\nAND EXTRACT(YEAR FROM c.due_date) = :year"
                            + "\nAND " + ACTIVE_MACHINE
                            + roleFilter(principal)
                            + (hasKw   ? "\nAND (c.machine_code ILIKE :kw OR c.machine_name ILIKE :kw)" : "")
                            + (hasDept ? "\nAND m.department = :department"                              : "")
                            + (hasRes  ? "\nAND c.results = :results"                                    : "")
                            + (hasCal  ? "\nAND c.calibration_status = :calibrationStatus"               : "");

                    String countSql =
                            "SELECT COUNT(*)\n"
                                    + "FROM calibration_record c\n"
                                    + "JOIN machine m ON m.machine_code = c.machine_code"
                                    + where;

                    String dataSql =
                            "SELECT\n"
                                    + "    c.id, c.machine_code, c.machine_name, c.years,\n"
                                    + "    c.due_date, c.start_date, c.certificate_date,\n"
                                    + "    c.results, c.criteria, c.measuring_range, c.accuracy,\n"
                                    + "    c.calibration_range, c.calibration_status,\n"
                                    + "    c.attachment, c.note, c.permissible_capacity,\n"
                                    + "    c.comment, c.resolution, c.max_uncertainty,\n"
                                    + "    c.mpe, c.check_mpe, c.check_resolution,\n"
                                    + "    c.check_result, c.reason_not_pass,\n"
                                    + "    m.responsible_person_name AS responsible_maintenance_name,\n"
                                    + "    m.department              AS machine_department_code,\n"
                                    + "    d.department              AS machine_department_name\n"
                                    + "FROM calibration_record c\n"
                                    + "JOIN machine m ON m.machine_code = c.machine_code\n"
                                    + DEPARTMENT_JOIN
                                    + where
                                    + "\nORDER BY m.department ASC NULLS LAST, c.due_date ASC NULLS LAST, c.id ASC"
                                    + "\nLIMIT :size OFFSET :offset";

                    UnaryOperator<GenericExecuteSpec> bindCommon = s -> {
                        s = s.bind("year", yr);
                        if (hasKw)   s = s.bind("kw", "%" + keyword.trim() + "%");
                        if (hasDept) s = s.bind("department", department.trim());
                        if (hasRes)  s = s.bind("results", results.trim());
                        if (hasCal)  s = s.bind("calibrationStatus", calibrationStatus.trim());
                        return s;
                    };

                    GenericExecuteSpec countSpec = bindCommon.apply(template.getDatabaseClient().sql(countSql));
                    GenericExecuteSpec dataSpec  = bindCommon.apply(template.getDatabaseClient().sql(dataSql))
                            .bind("size", size)
                            .bind("offset", (long) index * size);

                    Mono<Long> countMono = countSpec
                            .map((row, meta) -> row.get(0) instanceof Number n ? n.longValue() : 0L)
                            .one().defaultIfEmpty(0L);

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

                    return Mono.zip(countMono, dataFlux.collectList()).map(tuple -> {
                        long total = tuple.getT1();
                        return PagedResponse.<CalibrationResponseDTO>builder()
                                .success(true).message("Success")
                                .data(tuple.getT2())
                                .totalElements(total)
                                .totalPages((int) Math.ceil((double) total / size))
                                .index(index).size(size).build();
                    });
                })
                .onErrorResume(e -> {
                    log.error("Failed to fetch calibration page: {}", e.getMessage(), e);
                    return Mono.just(PagedResponse.<CalibrationResponseDTO>builder()
                            .success(false).message(e.getMessage())
                            .data(List.of()).totalElements(0L).totalPages(0)
                            .index(index).size(size).build());
                });
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // FILTER OPTIONS
    // ═══════════════════════════════════════════════════════════════════════════

    public Mono<CalibrationFilterOptionsDTO> getFilterOptions() {
        return currentPrincipal()
                .flatMap(principal -> {
                    String sql =
                            "SELECT DISTINCT\n"
                                    + "    EXTRACT(YEAR FROM c.due_date)::int       AS year,\n"
                                    + "    m.department                             AS department_code,\n"
                                    + "    COALESCE(d.department, m.department, '') AS department_name,\n"
                                    + "    d.division                               AS division,\n"
                                    + "    c.results                                AS results,\n"
                                    + "    c.calibration_status                     AS calibration_status\n"
                                    + "FROM calibration_record c\n"
                                    + "JOIN machine m ON m.machine_code = c.machine_code\n"
                                    + DEPARTMENT_JOIN
                                    + "WHERE c.due_date IS NOT NULL\n"
                                    + "  AND " + NOT_CANCELED + "\n"
                                    + "  AND " + ACTIVE_MACHINE
                                    + roleFilter(principal)
                                    + "\nORDER BY department_name ASC, division ASC";

                    return template.getDatabaseClient().sql(sql)
                            .map((row, meta) -> new Object[]{
                                    getIntValueNullable(row),
                                    row.get("department_code",    String.class),
                                    row.get("department_name",    String.class),
                                    row.get("division",           String.class),
                                    row.get("results",            String.class),
                                    row.get("calibration_status", String.class),
                            })
                            .all().collectList()
                            .map(rows -> {
                                Set<Integer>        years        = new TreeSet<>(Comparator.reverseOrder());
                                Map<String, String> depts        = new LinkedHashMap<>();
                                Set<String>         resultSet    = new LinkedHashSet<>();
                                Set<String>         calStatusSet = new LinkedHashSet<>();

                                for (Object[] r : rows) {
                                    if (r[0] != null) years.add((Integer) r[0]);
                                    String dc    = (String) r[1];
                                    String dn    = (String) r[2];
                                    String div   = (String) r[3];
                                    String label = StringUtils.hasText(div) ? dn + " - " + div : dn;
                                    if (StringUtils.hasText(dc))            depts.putIfAbsent(dc, label);
                                    if (StringUtils.hasText((String) r[4])) resultSet.add((String) r[4]);
                                    if (StringUtils.hasText((String) r[5])) calStatusSet.add((String) r[5]);
                                }

                                return CalibrationFilterOptionsDTO.builder()
                                        .years(new ArrayList<>(years))
                                        .departments(depts.entrySet().stream()
                                                .map(e -> CalibrationFilterOptionsDTO.DepartmentOption.builder()
                                                        .code(e.getKey()).name(e.getValue()).build())
                                                .toList())
                                        .results(new ArrayList<>(resultSet))
                                        .calibrationStatuses(new ArrayList<>(calStatusSet))
                                        .build();
                            });
                })
                .onErrorResume(e -> {
                    log.error("Failed to fetch calibration filter options: {}", e.getMessage(), e);
                    return Mono.just(CalibrationFilterOptionsDTO.builder()
                            .years(List.of()).departments(List.of())
                            .results(List.of()).calibrationStatuses(List.of()).build());
                });
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // DEPARTMENT SUMMARY
    // ═══════════════════════════════════════════════════════════════════════════

    public Flux<CalibrationDepartmentSummaryDTO> getDepartmentSummaryWithRole(Integer year) {
        return currentPrincipal()
                .flatMapMany(principal -> {
                    int yr = (year != null) ? year : LocalDate.now().getYear();

                    String sql =
                            "SELECT\n"
                                    + "    m.department AS department,\n"
                                    + "    CASE\n"
                                    + "        WHEN d.department IS NOT NULL AND d.division IS NOT NULL AND d.division != ''\n"
                                    + "            THEN d.department || ' - ' || d.division\n"
                                    + "        WHEN d.department IS NOT NULL THEN d.department\n"
                                    + "        ELSE m.department\n"
                                    + "    END AS department_name,\n"
                                    + "    COUNT(*)                                                AS total,\n"
                                    + "    COUNT(CASE WHEN c.results = 'PASS'   THEN 1 END)        AS total_pass,\n"
                                    + "    COUNT(CASE WHEN c.results = 'FAILED' THEN 1 END)        AS total_not_pass,\n"
                                    + "    COUNT(CASE WHEN " + IS_ON_TIME + " THEN 1 END)          AS total_on_time,\n"
                                    + "    COUNT(CASE WHEN " + IS_OVERDUE + " THEN 1 END)          AS total_overdue,\n"
                                    + "    COUNT(CASE WHEN c.certificate_date IS NOT NULL THEN 1 END) AS total_completed,\n"
                                    + "    COUNT(CASE WHEN c.certificate_date IS NULL     THEN 1 END) AS total_pending\n"
                                    + "FROM calibration_record c\n"
                                    + "JOIN machine m ON m.machine_code = c.machine_code\n"
                                    + DEPARTMENT_JOIN
                                    + "WHERE " + NOT_CANCELED + "\n"
                                    + "  AND EXTRACT(YEAR FROM c.due_date) = :year\n"
                                    + "  AND " + ACTIVE_MACHINE
                                    + roleFilter(principal)
                                    + "\nGROUP BY 1, 2"
                                    + "\nORDER BY department_name ASC";

                    return template.getDatabaseClient().sql(sql)
                            .bind("year", yr)
                            .map((row, meta) -> mapDepartmentSummary(row))
                            .all()
                            .onErrorResume(e -> {
                                log.error("Error fetching calibration department summary", e);
                                return Flux.empty();
                            });
                });
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // MONTHLY PLAN-ACTUAL
    // ═══════════════════════════════════════════════════════════════════════════

    public Flux<CalibrationMonthlyDTO> getMonthlyPlanActualSummary(Integer year) {
        return currentPrincipal()
                .flatMapMany(principal -> {
                    String sql =
                            "SELECT\n"
                                    + "    EXTRACT(YEAR  FROM c.due_date)::int AS year,\n"
                                    + "    EXTRACT(MONTH FROM c.due_date)::int AS month,\n"
                                    + "    m.responsible_person_id             AS member_id,\n"
                                    + "    MAX(" + MEMBER_NAME_EXPR + ")       AS member_name,\n"
                                    + "    COUNT(*)                                       AS total_plan,\n"
                                    + "    COUNT(CASE WHEN " + IS_ON_TIME + " THEN 1 END) AS total_on_time,\n"
                                    + "    COUNT(CASE WHEN " + IS_OVERDUE + " THEN 1 END) AS total_overdue\n"
                                    + "FROM calibration_record c\n"
                                    + "JOIN machine m ON m.machine_code = c.machine_code\n"
                                    + "LEFT JOIN member mb ON mb.id = m.responsible_person_id\n"
                                    + "WHERE c.due_date IS NOT NULL\n"
                                    + "  AND " + NOT_CANCELED + "\n"
                                    + "  AND " + ACTIVE_MACHINE
                                    + roleFilter(principal)
                                    + (year != null ? "\n  AND EXTRACT(YEAR FROM c.due_date) = :year" : "")
                                    + "\nGROUP BY 1, 2, 3"
                                    + "\nORDER BY year ASC, month ASC, member_name ASC";

                    GenericExecuteSpec spec = template.getDatabaseClient().sql(sql);
                    if (year != null) spec = spec.bind("year", year);

                    return spec
                            .map((row, meta) -> new Object[]{
                                    getIntValue(row, "year"),
                                    getIntValue(row, "month"),
                                    row.get("member_id",   Long.class),
                                    row.get("member_name", String.class),
                                    getLongValue(row, "total_plan"),
                                    getLongValue(row, "total_on_time"),
                                    getLongValue(row, "total_overdue"),
                            })
                            .all().collectList()
                            .flatMapMany(flatRows -> {
                                LinkedHashMap<String, List<CalibrationMonthlyDTO.ResponsibleSummary>> monthMap    = new LinkedHashMap<>();
                                Map<String, long[]>                                                   monthTotals = new LinkedHashMap<>();

                                for (Object[] r : flatRows) {
                                    String key = r[0] + "-" + r[1];
                                    monthMap.computeIfAbsent(key, k -> new ArrayList<>())
                                            .add(CalibrationMonthlyDTO.ResponsibleSummary.builder()
                                                    .memberId((Long) r[2]).memberName((String) r[3])
                                                    .totalPlan((long) r[4]).totalOnTime((long) r[5])
                                                    .totalOverdue((long) r[6]).build());
                                    monthTotals.merge(key, new long[]{ (long) r[4], (long) r[5], (long) r[6] },
                                            (a, b) -> new long[]{ a[0] + b[0], a[1] + b[1], a[2] + b[2] });
                                }

                                return Flux.fromIterable(monthMap.entrySet().stream().map(e -> {
                                    String[] p = e.getKey().split("-");
                                    long[]   t = monthTotals.get(e.getKey());
                                    return CalibrationMonthlyDTO.builder()
                                            .year(Integer.parseInt(p[0])).month(Integer.parseInt(p[1]))
                                            .totalPlan(t[0]).totalOnTime(t[1]).totalOverdue(t[2])
                                            .byResponsible(e.getValue()).build();
                                }).toList());
                            })
                            .onErrorResume(e -> {
                                log.error("Error fetching calibration monthly summary", e);
                                return Flux.empty();
                            });
                });
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // CALENDAR
    // ═══════════════════════════════════════════════════════════════════════════

    public Flux<CalibrationResponseDTO> getCalendarEvents(int year, int month) {
        return currentPrincipal()
                .flatMapMany(principal -> {
                    String sql =
                            "SELECT\n"
                                    + "    c.id, c.machine_code, c.machine_name, c.years, c.due_date,\n"
                                    + "    c.results, c.calibration_status,\n"
                                    + "    m.department                             AS machine_department_code,\n"
                                    + "    COALESCE(d.department, m.department, '') AS machine_department_name\n"
                                    + "FROM calibration_record c\n"
                                    + "JOIN machine m ON m.machine_code = c.machine_code\n"
                                    + DEPARTMENT_JOIN
                                    + "WHERE " + NOT_CANCELED + "\n"
                                    + "  AND EXTRACT(YEAR  FROM c.due_date) = :year\n"
                                    + "  AND EXTRACT(MONTH FROM c.due_date) = :month\n"
                                    + "  AND " + ACTIVE_MACHINE
                                    + roleFilter(principal)
                                    + "\nORDER BY c.due_date ASC, c.id ASC";

                    return template.getDatabaseClient().sql(sql)
                            .bind("year", year).bind("month", month)
                            .map((row, meta) -> CalibrationResponseDTO.builder()
                                    .id(row.get("id", Long.class))
                                    .machineCode(row.get("machine_code", String.class))
                                    .machineName(row.get("machine_name", String.class))
                                    .years(row.get("years", String.class))
                                    .dueDate(row.get("due_date", LocalDate.class))
                                    .results(row.get("results", String.class))
                                    .calibrationStatus(row.get("calibration_status", String.class))
                                    .machineDepartmentCode(row.get("machine_department_code", String.class))
                                    .machineDepartmentName(row.get("machine_department_name", String.class))
                                    .build())
                            .all()
                            .onErrorResume(e -> {
                                log.error("Failed to fetch calibration calendar: {}", e.getMessage(), e);
                                return Flux.empty();
                            });
                });
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // GET BY ID / BY MACHINE CODE
    // ═══════════════════════════════════════════════════════════════════════════

    public Mono<ApiResponse<CalibrationResponseDTO>> getById(Long id) {
        return currentPrincipal()
                .switchIfEmpty(Mono.error(new ThrowException("MS401", "Unauthenticated")))
                .flatMap(p -> canViewRecord(p, id))
                .flatMap(allowed -> !allowed
                        ? Mono.just(ApiResponse.<CalibrationResponseDTO>error("MS018", "Calibration not found"))
                        : template.selectOne(Query.query(Criteria.where("id").is(id)), CalibrationRecord.class)
                        .map(cal -> ApiResponse.success("MS017", CalibrationResponseDTO.from(cal)))
                        .switchIfEmpty(Mono.just(ApiResponse.error("MS018", "Calibration not found"))))
                .onErrorResume(e -> {
                    log.error("Failed to fetch calibration: {}", e.getMessage(), e);
                    return Mono.just(ApiResponse.error("MS019", e.getMessage()));
                });
    }

    public Mono<ApiResponse<List<CalibrationResponseDTO>>> getByMachineCode(String machineCode) {
        return currentPrincipal()
                .switchIfEmpty(Mono.error(new ThrowException("MS401", "Unauthenticated")))
                .flatMap(p -> canViewMachine(p, machineCode))
                .flatMap(allowed -> !allowed
                        ? Mono.just(ApiResponse.<List<CalibrationResponseDTO>>error("MS018", "Data not found"))
                        : template.select(
                                Query.query(Criteria.where("machine_code").is(machineCode))
                                        .sort(Sort.by("due_date").descending()),
                                CalibrationRecord.class)
                        .collectList()
                        .map(records -> records.isEmpty()
                                ? ApiResponse.<List<CalibrationResponseDTO>>error("MS018", "Data not found")
                                : ApiResponse.success("MS017",
                                records.stream().map(CalibrationResponseDTO::from).toList())))
                .onErrorResume(e -> {
                    log.error("Failed to fetch calibration by machine code: {}", e.getMessage(), e);
                    return Mono.just(ApiResponse.error("MS019", e.getMessage()));
                });
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // VALIDATE
    // ═══════════════════════════════════════════════════════════════════════════

    public Mono<CalibrationDTO> validateData(CalibrationDTO dto) {
        if (dto.getId() == null)
            return Mono.error(new ThrowException("MS018", "Calibration id is required"));
        return template.selectOne(Query.query(Criteria.where("id").is(dto.getId())), CalibrationRecord.class)
                .switchIfEmpty(Mono.error(new ThrowException("MS018", "Calibration not found")))
                .map(existing -> dto);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PRIVATE HELPERS
    // ═══════════════════════════════════════════════════════════════════════════

    private GenericExecuteSpec buildUpdateSpec(CalibrationDTO dto, Long memberId) {
        List<String> sets   = new ArrayList<>();
        List<Object> values = new ArrayList<>();

        addParam(sets, values, "due_date",             dto.getDueDate());
        addParam(sets, values, "start_date",           dto.getStartDate());
        addParam(sets, values, "certificate_date",     dto.getCertificateDate());
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

        addParam(sets, values, "updated_at", LocalDateTime.now());
        addParam(sets, values, "updated_by", memberId);

        values.add(dto.getId());
        String sql = "UPDATE calibration_record SET "
                + String.join(", ", sets) + " WHERE id = $" + values.size();

        GenericExecuteSpec spec = template.getDatabaseClient().sql(sql);
        for (int i = 0; i < values.size(); i++) spec = spec.bind(i, values.get(i));
        return spec;
    }

    private void addParam(List<String> sets, List<Object> values, String col, Object val) {
        if (val != null) { values.add(val); sets.add(col + " = $" + values.size()); }
    }

    private CalibrationDepartmentSummaryDTO mapDepartmentSummary(Row row) {
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
            log.error("Error mapping calibration summary row", e);
            throw new RuntimeException("Error mapping calibration department summary data", e);
        }
    }

    private Long getLongValue(Row row, String col) {
        Object v = row.get(col);
        return switch (v) { case Long l -> l; case Number n -> n.longValue(); case null, default -> 0L; };
    }

    private int getIntValue(Row row, String col) {
        Object v = row.get(col);
        return switch (v) { case Integer i -> i; case Number n -> n.intValue(); case null, default -> 0; };
    }

    private Integer getIntValueNullable(Row row) {
        Object v = row.get("year");
        return switch (v) { case Integer i -> i; case Number n -> n.intValue(); case null, default -> null; };
    }
}