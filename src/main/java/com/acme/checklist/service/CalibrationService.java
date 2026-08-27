package com.acme.checklist.service;

import com.acme.checklist.entity.*;
import com.acme.checklist.entity.Member;
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

    // ═══════════════════════════════════════════════════════════════════════════
    // ROLE FILTER HELPERS
    //
    // กฎเดียวกันทุก method:
    //   ADMIN      → ไม่ filter (เห็นทุก record)
    //   MANAGER    → filter ด้วย manager_id
    //   SUPERVISOR → filter ด้วย supervisor_id
    //   USER/อื่น  → filter ด้วย responsible_person_id
    //
    // มี 2 รูปแบบ JOIN:
    //   roleFilterJoin(p)    — ใช้กับ query ที่ JOIN machine m อยู่แล้ว
    //                          → เพิ่ม AND m.<col> = <id> ใน WHERE
    //   roleFilterExists(p)  — ใช้กับ query ที่ไม่ JOIN machine (หรือต้องการ safe)
    //                          → เพิ่ม AND EXISTS (SELECT 1 FROM machine m2 ...)
    // ═══════════════════════════════════════════════════════════════════════════

    private static String roleFilterJoin(MemberPrincipal p) {
        return switch (p.role()) {
            case "ADMIN"            -> "";
            case "DEPARTMENT_ADMIN" -> p.departmentId() != null
                    ? "AND m.department LIKE (SELECT LEFT(d.department_code, LENGTH(d.department_code) - 1) || '%' FROM department d WHERE d.id = " + p.departmentId() + ")"
                    : "AND 1=0";
            case "MANAGER"          -> "AND m.manager_id    = " + p.memberId();
            case "SUPERVISOR"       -> "AND m.supervisor_id = " + p.memberId();
            default                 -> "AND m.responsible_person_id = " + p.memberId();
        };
    }

    private static String roleFilterExists(MemberPrincipal p) {
        String statusCond = "AND m2.machine_status IN ('OPERATIONAL', 'NON-OPERATIONAL', 'UNDER MAINTENANCE')";
        return switch (p.role()) {
            case "ADMIN"            -> "AND EXISTS (SELECT 1 FROM machine m2 WHERE m2.machine_code = c.machine_code " + statusCond + ")";
            case "DEPARTMENT_ADMIN" -> p.departmentId() != null
                    ? "AND EXISTS (SELECT 1 FROM machine m2 WHERE m2.machine_code = c.machine_code AND m2.department LIKE (SELECT LEFT(d.department_code, LENGTH(d.department_code) - 1) || '%' FROM department d WHERE d.id = " + p.departmentId() + ") " + statusCond + ")"
                    : "AND 1=0";
            case "MANAGER"          -> "AND EXISTS (SELECT 1 FROM machine m2 WHERE m2.machine_code = c.machine_code AND m2.manager_id = " + p.memberId() + " " + statusCond + ")";
            case "SUPERVISOR"       -> "AND EXISTS (SELECT 1 FROM machine m2 WHERE m2.machine_code = c.machine_code AND m2.supervisor_id = " + p.memberId() + " " + statusCond + ")";
            default                 -> "AND EXISTS (SELECT 1 FROM machine m2 WHERE m2.machine_code = c.machine_code AND m2.responsible_person_id = " + p.memberId() + " " + statusCond + ")";
        };
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // UPDATE
    // ═══════════════════════════════════════════════════════════════════════════

    public Mono<ApiResponse<Void>> update(CalibrationDTO dto) {
        return validateData(dto)
                .flatMap(v -> {
                    DatabaseClient.GenericExecuteSpec spec = buildUpdateSpec(v);
                    if (spec == null) return Mono.just(ApiResponse.<Void>success("MS003"));
                    return spec.then().then(Mono.just(ApiResponse.<Void>success("MS003")));
                })
                .onErrorResume(e -> Mono.just(ApiResponse.error("MS004", e.getMessage())));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // GET PAGE
    // ใช้ LEFT JOIN machine เพราะต้องแสดง department / responsible_person_name
    // และต้องการ filter ด้วย department
    // ADMIN: roleFragment = "" → ไม่ filter, ไม่ bind memberId
    // ปัญหา duplicate: machine_code มีหลาย row ใน machine → ใช้ DISTINCT c.id
    // ═══════════════════════════════════════════════════════════════════════════

    public Mono<PagedResponse<CalibrationResponseDTO>> getPage(
            String keyword, Integer year, String department,
            String results, String calibrationStatus,
            int index, int size) {

        return ReactiveSecurityContextHolder.getContext()
                .mapNotNull(ctx -> (MemberPrincipal) Objects.requireNonNull(ctx.getAuthentication()).getPrincipal())
                .flatMap(principal -> {
                    String  role          = principal.role();
                    Long    memberId      = principal.memberId();
                    boolean hasKw         = StringUtils.hasText(keyword);
                    int     effectiveYear = (year != null) ? year : LocalDate.now().getYear();
                    boolean hasDept       = StringUtils.hasText(department);
                    boolean hasRes        = StringUtils.hasText(results);
                    boolean hasCal        = StringUtils.hasText(calibrationStatus);

                    // role filter: ใช้ bindable param สำหรับ non-ADMIN (ป้องกัน SQL injection)
                    String roleFragment = switch (role) {
                        case "ADMIN"            -> "";
                        case "DEPARTMENT_ADMIN" -> principal.departmentId() != null
                                ? "AND m.department LIKE (SELECT LEFT(d.department_code, LENGTH(d.department_code) - 1) || '%' FROM department d WHERE d.id = " + principal.departmentId() + ")"
                                : "AND 1=0";
                        case "MANAGER"          -> "AND m.manager_id    = :memberId";
                        case "SUPERVISOR"       -> "AND m.supervisor_id = :memberId";
                        default                 -> "AND m.responsible_person_id = :memberId";
                    };

                    String kwFragment   = hasKw   ? "AND (c.machine_code ILIKE :kw OR c.machine_name ILIKE :kw)" : "";
                    String deptFragment = hasDept ? "AND m.department = :department"                              : "";
                    String resFragment  = hasRes  ? "AND c.results = :results"                                    : "";
                    String calFragment  = hasCal  ? "AND c.calibration_status = :calibrationStatus"               : "";

                    String where = """
                            WHERE (c.is_canceled = FALSE OR c.is_canceled IS NULL)
                              AND EXTRACT(YEAR FROM c.due_date) = :year
                              AND m.machine_status IN ('OPERATIONAL', 'NON-OPERATIONAL', 'UNDER MAINTENANCE')
                            """ + roleFragment + " " + kwFragment + " "
                            + deptFragment + " " + resFragment + " " + calFragment;

                    // COUNT ใช้ subquery เพื่อนับ distinct c.id ป้องกัน duplicate จาก machine
                    String countSql = """
                            SELECT COUNT(*) FROM (
                                SELECT DISTINCT c.id
                                FROM calibration_record c
                                LEFT JOIN machine m ON m.machine_code = c.machine_code
                            """ + where + ") AS sub";

                    String dataSql = """
                            SELECT DISTINCT ON (c.id)
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
                            LEFT JOIN department d ON d.department_code::text = m.department
                            """ + where + """
                            ORDER BY c.id, m.department ASC NULLS LAST, c.due_date ASC NULLS LAST
                            LIMIT :size OFFSET :offset
                            """;

                    DatabaseClient.GenericExecuteSpec countSpec = template.getDatabaseClient().sql(countSql);
                    DatabaseClient.GenericExecuteSpec dataSpec  = template.getDatabaseClient().sql(dataSql);

                    // bind params เหมือนกันทั้งคู่
                    countSpec = countSpec.bind("year", effectiveYear);
                    dataSpec  = dataSpec.bind("year",  effectiveYear);
                    // ADMIN และ DEPARTMENT_ADMIN ไม่ใช้ :memberId param
                    if (!"ADMIN".equals(role) && !"DEPARTMENT_ADMIN".equals(role)) {
                        countSpec = countSpec.bind("memberId", memberId);
                        dataSpec  = dataSpec.bind("memberId",  memberId);
                    }
                    if (hasKw) {
                        String kw = "%" + keyword.trim() + "%";
                        countSpec = countSpec.bind("kw", kw);
                        dataSpec  = dataSpec.bind("kw",  kw);
                    }
                    if (hasDept) {
                        countSpec = countSpec.bind("department", department.trim());
                        dataSpec  = dataSpec.bind("department",  department.trim());
                    }
                    if (hasRes) {
                        countSpec = countSpec.bind("results", results.trim());
                        dataSpec  = dataSpec.bind("results",  results.trim());
                    }
                    if (hasCal) {
                        countSpec = countSpec.bind("calibrationStatus", calibrationStatus.trim());
                        dataSpec  = dataSpec.bind("calibrationStatus",  calibrationStatus.trim());
                    }
                    dataSpec = dataSpec.bind("size", size).bind("offset", (long) index * size);

                    Mono<Long> countMono = countSpec
                            .map((row, meta) -> {
                                Object v = row.get(0);
                                return v instanceof Number n ? n.longValue() : 0L;
                            })
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

                    return Mono.zip(countMono, dataFlux.collectList())
                            .map(tuple -> {
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
    // ต้อง JOIN machine เพื่อดึง department → ใช้ roleFilterJoin
    // ═══════════════════════════════════════════════════════════════════════════

    public Mono<CalibrationFilterOptionsDTO> getFilterOptions() {
        return ReactiveSecurityContextHolder.getContext()
                .mapNotNull(ctx -> (MemberPrincipal) Objects.requireNonNull(ctx.getAuthentication()).getPrincipal())
                .flatMap(principal -> {
                    String sql = """
                            SELECT DISTINCT
                                EXTRACT(YEAR FROM c.due_date)::int        AS year,
                                m.department                               AS department_code,
                                COALESCE(d.department, m.department, '')   AS department_name,
                                d.division                                  AS division,
                                c.results                                   AS results,
                                c.calibration_status                        AS calibration_status
                            FROM calibration_record c
                            LEFT JOIN machine m ON m.machine_code = c.machine_code
                            LEFT JOIN department d ON d.department_code::text = m.department
                            WHERE c.due_date IS NOT NULL
                              AND m.machine_status IN ('OPERATIONAL', 'NON-OPERATIONAL', 'UNDER MAINTENANCE')
                            """ + roleFilterJoin(principal) + """
                            ORDER BY department_name ASC, division ASC
                            """;

                    return template.getDatabaseClient().sql(sql)
                            .map((row, meta) -> new Object[]{
                                    getIntValueNullable(row),
                                    row.get("department_code",   String.class),
                                    row.get("department_name",   String.class),
                                    row.get("division",           String.class),
                                    row.get("results",            String.class),
                                    row.get("calibration_status", String.class),
                            })
                            .all().collectList()
                            .map(rows -> {
                                Set<Integer>        years       = new TreeSet<>(Comparator.reverseOrder());
                                Map<String, String> depts       = new LinkedHashMap<>();
                                Set<String>         resultSet   = new LinkedHashSet<>();
                                Set<String>         calStatusSet= new LinkedHashSet<>();

                                for (Object[] r : rows) {
                                    if (r[0] != null) years.add((Integer) r[0]);
                                    String dc  = (String) r[1];
                                    String dn  = (String) r[2];
                                    String div = (String) r[3];
                                    String label = StringUtils.hasText(div) ? dn + " - " + div : dn;
                                    if (StringUtils.hasText(dc))           depts.putIfAbsent(dc, label);
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
    // JOIN machine เพื่อ GROUP BY department → ใช้ roleFilterJoin
    // ═══════════════════════════════════════════════════════════════════════════

    public Flux<CalibrationDepartmentSummaryDTO> getDepartmentSummaryWithRole(Integer year) {
        return ReactiveSecurityContextHolder.getContext()
                .mapNotNull(ctx -> (MemberPrincipal) Objects.requireNonNull(ctx.getAuthentication()).getPrincipal())
                .flatMapMany(principal -> {
                    int    effectiveYear = (year != null) ? year : LocalDate.now().getYear();

                    // DISTINCT ON (c.id) ป้องกัน duplicate จาก machine มีหลาย row
                    String sql = """
                        SELECT
                            department,
                            department_name,
                            COUNT(*)                                                     AS total,
                            COUNT(CASE WHEN results = 'PASS'               THEN 1 END)  AS total_pass,
                            COUNT(CASE WHEN results = 'FAILED'             THEN 1 END)  AS total_not_pass,
                            COUNT(CASE WHEN calibration_status = 'ON TIME' THEN 1 END)  AS total_on_time,
                            COUNT(CASE WHEN calibration_status = 'OVERDUE' THEN 1 END)  AS total_overdue,
                            COUNT(CASE WHEN certificate_date IS NOT NULL   THEN 1 END)  AS total_completed,
                            COUNT(CASE WHEN certificate_date IS NULL       THEN 1 END)  AS total_pending
                        FROM (
                            SELECT DISTINCT ON (c.id)
                                c.id,
                                c.results,
                                c.calibration_status,
                                c.certificate_date,
                                m.department AS department,
                                CASE
                                    WHEN d.department IS NOT NULL AND d.division IS NOT NULL AND d.division != ''
                                        THEN d.department || ' - ' || d.division
                                    WHEN d.department IS NOT NULL THEN d.department
                                    ELSE m.department
                                END AS department_name
                            FROM calibration_record c
                            JOIN machine m ON c.machine_code = m.machine_code
                            LEFT JOIN department d ON m.department = d.department_code::text
                            WHERE (c.is_canceled = FALSE OR c.is_canceled IS NULL)
                              AND EXTRACT(YEAR FROM c.due_date) = """ + effectiveYear + """
                              AND m.machine_status IN ('OPERATIONAL', 'NON-OPERATIONAL', 'UNDER MAINTENANCE')
                            """ + roleFilterJoin(principal) + """
                            ORDER BY c.id
                        ) sub
                        GROUP BY department, department_name
                        HAVING COUNT(*) > 0
                        ORDER BY department_name ASC
                        """;

                    return template.getDatabaseClient().sql(sql)
                            .map((row, metadata) -> mapDepartmentSummary(row))
                            .all()
                            .onErrorResume(e -> {
                                log.error("Error fetching calibration department summary", e);
                                return Flux.empty();
                            });
                });
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // MONTHLY PLAN-ACTUAL
    // ไม่ JOIN machine โดยตรง (GROUP BY due_date) → ใช้ roleFilterExists
    // ═══════════════════════════════════════════════════════════════════════════

    public Flux<CalibrationMonthlyDTO> getMonthlyPlanActualSummary(Integer year) {
        return ReactiveSecurityContextHolder.getContext()
                .mapNotNull(ctx -> (MemberPrincipal) Objects.requireNonNull(ctx.getAuthentication()).getPrincipal())
                .flatMapMany(principal -> {
                    String yearFilter = (year != null)
                            ? "AND EXTRACT(YEAR FROM c.due_date) = " + year : "";

                    String sql = """
                        SELECT
                            EXTRACT(YEAR  FROM c.due_date)::int AS year,
                            EXTRACT(MONTH FROM c.due_date)::int AS month,
                            (SELECT m2.responsible_person_id FROM machine m2
                             WHERE m2.machine_code = c.machine_code LIMIT 1) AS member_id,
                            MAX(COALESCE(
                                NULLIF(TRIM(mb.first_name || ' ' || mb.last_name), ''),
                                mb.first_name, mb.user_name, 'Unassigned'))  AS member_name,
                            COUNT(*)                                          AS total_plan,
                            COUNT(CASE WHEN c.certificate_date IS NOT NULL
                                       AND c.certificate_date <= c.due_date  THEN 1 END) AS total_on_time,
                            COUNT(CASE WHEN (c.certificate_date IS NOT NULL
                                            AND c.certificate_date > c.due_date)
                                        OR   c.certificate_date IS NULL      THEN 1 END) AS total_overdue
                        FROM calibration_record c
                        LEFT JOIN member mb ON mb.id = (
                            SELECT m2.responsible_person_id FROM machine m2
                            WHERE m2.machine_code = c.machine_code LIMIT 1)
                        WHERE c.due_date IS NOT NULL
                        """ + roleFilterExists(principal) + " " + yearFilter + """
                        GROUP BY
                            EXTRACT(YEAR  FROM c.due_date),
                            EXTRACT(MONTH FROM c.due_date),
                            (SELECT m2.responsible_person_id FROM machine m2
                             WHERE m2.machine_code = c.machine_code LIMIT 1)
                        ORDER BY year ASC, month ASC, member_name ASC
                        """;

                    return template.getDatabaseClient().sql(sql)
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
                                LinkedHashMap<String, List<CalibrationMonthlyDTO.ResponsibleSummary>> monthMap =
                                        new LinkedHashMap<>();
                                Map<String, long[]> monthTotals = new LinkedHashMap<>();

                                for (Object[] r : flatRows) {
                                    String key = r[0] + "-" + r[1];
                                    monthMap.computeIfAbsent(key, k -> new ArrayList<>())
                                            .add(CalibrationMonthlyDTO.ResponsibleSummary.builder()
                                                    .memberId((Long) r[2]).memberName((String) r[3])
                                                    .totalPlan((long) r[4]).totalOnTime((long) r[5])
                                                    .totalOverdue((long) r[6]).build());
                                    monthTotals.merge(key, new long[]{ (long)r[4], (long)r[5], (long)r[6] },
                                            (a, b) -> new long[]{ a[0]+b[0], a[1]+b[1], a[2]+b[2] });
                                }

                                return Flux.fromIterable(monthMap.entrySet().stream().map(e -> {
                                    String[] p  = e.getKey().split("-");
                                    long[]   t  = monthTotals.get(e.getKey());
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
    // ADMIN  → ไม่ JOIN (ป้องกัน duplicate, เห็นครบ)
    // Others → JOIN + DISTINCT ON (c.id) เพื่อ filter scope
    // ═══════════════════════════════════════════════════════════════════════════

    public Flux<CalibrationResponseDTO> getCalendarEvents(int year, int month) {
        return ReactiveSecurityContextHolder.getContext()
                .mapNotNull(ctx -> (MemberPrincipal) Objects.requireNonNull(ctx.getAuthentication()).getPrincipal())
                .flatMapMany(principal -> {
                    final String sql;
                    // รวม ADMIN และ role อื่นเป็น query เดียว
                    // JOIN machine เสมอเพื่อ filter machine_status
                    // DISTINCT ON (c.id) ป้องกัน duplicate จาก machine มีหลาย row
                    sql = """
                        SELECT DISTINCT ON (c.id)
                            c.id, c.machine_code, c.machine_name, c.years, c.due_date,
                            c.results, c.calibration_status,
                            m.department                             AS machine_department_code,
                            COALESCE(d.department, m.department, '') AS machine_department_name
                        FROM calibration_record c
                        JOIN machine m ON m.machine_code = c.machine_code
                        LEFT JOIN department d ON d.department_code::text = m.department
                        WHERE (c.is_canceled = FALSE OR c.is_canceled IS NULL)
                          AND EXTRACT(YEAR  FROM c.due_date) = :year
                          AND EXTRACT(MONTH FROM c.due_date) = :month
                          AND m.machine_status IN ('OPERATIONAL', 'NON-OPERATIONAL', 'UNDER MAINTENANCE')
                        """ + roleFilterJoin(principal) + """
                        ORDER BY c.id, c.due_date ASC
                        """;

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
    // GET BY ID / GET BY MACHINE CODE
    // ═══════════════════════════════════════════════════════════════════════════

    public Mono<ApiResponse<CalibrationResponseDTO>> getById(Long id) {
        return template.selectOne(Query.query(Criteria.where("id").is(id)), CalibrationRecord.class)
                .flatMap(cal -> {
                    List<Long> memberIds = new ArrayList<>();
                    if (cal.getCreatedBy() != null) memberIds.add(cal.getCreatedBy());
                    if (cal.getUpdatedBy() != null) memberIds.add(cal.getUpdatedBy());
                    Mono<Map<Long, Member>> membersMono = memberIds.isEmpty()
                            ? Mono.just(new HashMap<>()) : commonService.fetchMembersByIds(memberIds);
                    return membersMono.map(m -> ApiResponse.success("MS017", CalibrationResponseDTO.from(cal)));
                })
                .switchIfEmpty(Mono.just(ApiResponse.error("MS018", "Calibration not found")))
                .onErrorResume(e -> {
                    log.error("Failed to fetch calibration: {}", e.getMessage(), e);
                    return Mono.just(ApiResponse.error("MS019", e.getMessage()));
                });
    }

    public Mono<ApiResponse<List<CalibrationResponseDTO>>> getByMachineCode(String machineCode) {
        return template.select(
                        Query.query(Criteria.where("machine_code").is(machineCode))
                                .sort(Sort.by("due_date").descending()),
                        CalibrationRecord.class)
                .collectList()
                .flatMap(records -> {
                    if (records.isEmpty())
                        return Mono.just(ApiResponse.<List<CalibrationResponseDTO>>error("MS018", "Data not found"));
                    return Mono.just(ApiResponse.success("MS017",
                            records.stream().map(CalibrationResponseDTO::from).toList()));
                })
                .onErrorResume(e -> {
                    log.error("Failed to fetch calibration by machine code: {}", e.getMessage(), e);
                    return Mono.just(ApiResponse.error("MS019", e.getMessage()));
                });
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // VALIDATE
    // ═══════════════════════════════════════════════════════════════════════════

    public Mono<CalibrationDTO> validateData(CalibrationDTO dto) {
        return template.selectOne(Query.query(Criteria.where("id").is(dto.getId())), CalibrationRecord.class)
                .switchIfEmpty(Mono.error(new ThrowException("MS018", "Calibration not found")))
                .flatMap(existing -> Mono.just(dto));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PRIVATE HELPERS
    // ═══════════════════════════════════════════════════════════════════════════

    private DatabaseClient.GenericExecuteSpec buildUpdateSpec(CalibrationDTO dto) {
        List<String> sets   = new ArrayList<>();
        List<Object> values = new ArrayList<>();

        addDateParam(sets, values, "due_date",         dto.getDueDate());
        addDateParam(sets, values, "start_date",       dto.getStartDate());
        addDateParam(sets, values, "certificate_date", dto.getCertificateDate());
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
                + String.join(", ", sets) + " WHERE id = $" + values.size();

        DatabaseClient.GenericExecuteSpec spec = template.getDatabaseClient().sql(sql);
        for (int i = 0; i < values.size(); i++) spec = spec.bind(i, values.get(i));
        return spec;
    }

    private void addDateParam(List<String> sets, List<Object> values, String col, LocalDate val) {
        if (val != null) { values.add(val); sets.add(col + " = $" + values.size()); }
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
        return switch (v) {
            case Long l        -> l;
            case Number n      -> n.longValue();
            case null, default -> 0L;
        };
    }

    private int getIntValue(Row row, String col) {
        Object v = row.get(col);
        return switch (v) {
            case Integer i     -> i;
            case Number n      -> n.intValue();
            case null, default -> 0;
        };
    }

    private Integer getIntValueNullable(Row row) {
        Object v = row.get("year");
        return switch (v) {
            case Integer i     -> i;
            case Number n      -> n.intValue();
            case null, default -> null;
        };
    }
}