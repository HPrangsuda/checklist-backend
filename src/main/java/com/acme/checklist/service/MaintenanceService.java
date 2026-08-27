package com.acme.checklist.service;

import com.acme.checklist.entity.MaintenanceRecord;
import com.acme.checklist.entity.Member;
import com.acme.checklist.exception.ThrowException;
import com.acme.checklist.payload.ApiResponse;
import com.acme.checklist.payload.MemberPrincipal;
import com.acme.checklist.payload.PagedResponse;
import com.acme.checklist.payload.maintenance.*;
import io.r2dbc.spi.Row;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.data.relational.core.query.Criteria;
import org.springframework.data.relational.core.query.Query;
import org.springframework.data.relational.core.query.Update;
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
public class MaintenanceService {

    private final R2dbcEntityTemplate template;
    private final CommonService commonService;

    // ═══════════════════════════════════════════════════════════════════════════
    // ROLE FILTER HELPERS
    //
    // roleFilterJoin   — query ที่ JOIN machine m อยู่แล้ว
    // roleFilterExists — query ที่ไม่ JOIN machine (ใช้ EXISTS subquery)
    //
    // DEPARTMENT_ADMIN: เห็น department prefix ตัวเอง (เช่น "61%" เห็น 611,612)
    // ═══════════════════════════════════════════════════════════════════════════

    private static String roleFilterJoin(MemberPrincipal p) {
        return switch (p.role()) {
            case "ADMIN"            -> "";
            case "DEPARTMENT_ADMIN" -> p.departmentId() != null
                    ? "AND m.department LIKE (SELECT LEFT(d.department_code, LENGTH(d.department_code) - 1) || '%' FROM department d WHERE d.id = " + p.departmentId() + ")"
                    : "AND 1=0";
            case "MANAGER"          -> "AND m.manager_id    = " + p.memberId();
            case "SUPERVISOR"       -> "AND m.supervisor_id = " + p.memberId();
            default                 -> "AND (m.responsible_person_id = " + p.memberId() + " OR mr.responsible_maintenance = " + p.memberId() + ")";
        };
    }

    private static String roleFilterExists(MemberPrincipal p) {
        Long memberId = p.memberId();
        return switch (p.role()) {
            case "ADMIN"            -> "";
            case "DEPARTMENT_ADMIN" -> p.departmentId() != null
                    ? "AND EXISTS (SELECT 1 FROM machine m2 WHERE m2.machine_code = mr.machine_code AND m2.department LIKE (SELECT LEFT(d.department_code, LENGTH(d.department_code) - 1) || '%' FROM department d WHERE d.id = " + p.departmentId() + "))"
                    : "AND 1=0";
            case "MANAGER"          -> "AND EXISTS (SELECT 1 FROM machine m2 WHERE m2.machine_code = mr.machine_code AND m2.manager_id = " + memberId + ")";
            case "SUPERVISOR"       -> "AND EXISTS (SELECT 1 FROM machine m2 WHERE m2.machine_code = mr.machine_code AND m2.supervisor_id = " + memberId + ")";
            default                 -> "AND (EXISTS (SELECT 1 FROM machine m2 WHERE m2.machine_code = mr.machine_code AND m2.responsible_person_id = " + memberId + ") OR mr.responsible_maintenance = " + memberId + ")";
        };
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // UPDATE
    // ═══════════════════════════════════════════════════════════════════════════

    public Mono<ApiResponse<Void>> update(MaintenanceDTO dto) {
        return validateData(dto, true)
                .flatMap(validated -> {
                    Update update = buildUpdateFromDTO(validated);
                    return commonService.update(dto.getId(), update, MaintenanceRecord.class)
                            .then(Mono.just(ApiResponse.<Void>success("MS001")));
                })
                .onErrorResume(e -> {
                    log.error("Failed to update the maintenance: {}", e.getMessage());
                    return Mono.just(ApiResponse.error("MS001", e.getMessage()));
                });
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // GET PAGE
    // JOIN machine เพื่อแสดง department / responsible_person_name
    // DEPARTMENT_ADMIN: roleFragment ใช้ LIKE prefix (ไม่ใช้ :memberId param)
    // ═══════════════════════════════════════════════════════════════════════════

    public Mono<PagedResponse<MaintenanceResponseDTO>> getPage(
            String keyword, Integer year, String department, String status,
            int index, int size) {

        return ReactiveSecurityContextHolder.getContext()
                .mapNotNull(ctx -> (MemberPrincipal) Objects.requireNonNull(ctx.getAuthentication()).getPrincipal())
                .flatMap(principal -> {
                    String  role          = principal.role();
                    Long    memberId      = principal.memberId();
                    boolean hasKw         = StringUtils.hasText(keyword);
                    int     effectiveYear = (year != null) ? year : LocalDate.now().getYear();
                    boolean hasDept       = StringUtils.hasText(department);
                    boolean hasSt         = StringUtils.hasText(status);

                    // DEPARTMENT_ADMIN ใช้ inline SQL ไม่ใช้ :memberId param
                    String roleFragment = switch (role) {
                        case "ADMIN"            -> "";
                        case "DEPARTMENT_ADMIN" -> principal.departmentId() != null
                                ? "AND m.department LIKE (SELECT LEFT(d.department_code, LENGTH(d.department_code) - 1) || '%' FROM department d WHERE d.id = " + principal.departmentId() + ")"
                                : "AND 1=0";
                        case "MANAGER"          -> "AND m.manager_id    = :memberId";
                        case "SUPERVISOR"       -> "AND m.supervisor_id = :memberId";
                        default                 -> "AND (m.responsible_person_id = :memberId OR mr.responsible_maintenance = :memberId)";
                    };

                    String kwFragment   = hasKw   ? "AND (mr.machine_code ILIKE :kw OR mr.machine_name ILIKE :kw)" : "";
                    String deptFragment = hasDept ? "AND m.department = :department" : "";
                    String stFragment   = hasSt   ? "AND mr.status = :status"        : "";

                    String where = "WHERE 1=1 "
                            + "AND (mr.is_canceled = FALSE OR mr.is_canceled IS NULL) "
                            + roleFragment   + " "
                            + kwFragment     + " "
                            + "AND EXTRACT(YEAR FROM mr.due_date) = :year "
                            + deptFragment   + " "
                            + stFragment;

                    String countSql = """
                            SELECT COUNT(*)
                            FROM maintenance_record mr
                            LEFT JOIN machine m ON m.machine_code = mr.machine_code
                            """ + where;

                    String dataSql = """
                            SELECT
                                mr.id,
                                mr.machine_code,
                                mr.machine_name,
                                mr.years,
                                mr.round,
                                mr.due_date,
                                mr.plan_date,
                                mr.start_date,
                                mr.actual_date,
                                mr.status,
                                mr.maintenance_by,
                                mr.responsible_maintenance,
                                mr.note,
                                mr.attachment,
                                mr.checklist_record_id,
                                m.responsible_person_name AS responsible_maintenance_name,
                                m.department              AS machine_department_code,
                                d.department              AS machine_department_name
                            FROM maintenance_record mr
                            LEFT JOIN machine m ON m.machine_code = mr.machine_code
                            LEFT JOIN department d ON d.department_code::text = m.department
                            """ + where + """
                            ORDER BY m.department ASC NULLS LAST, mr.due_date ASC NULLS LAST
                            LIMIT :size OFFSET :offset
                            """;

                    DatabaseClient.GenericExecuteSpec countSpec = template.getDatabaseClient().sql(countSql);
                    DatabaseClient.GenericExecuteSpec dataSpec  = template.getDatabaseClient().sql(dataSql);

                    // bind :memberId เฉพาะ role ที่ใช้ param (ไม่รวม ADMIN และ DEPARTMENT_ADMIN)
                    if (!"ADMIN".equals(role) && !"DEPARTMENT_ADMIN".equals(role)) {
                        countSpec = countSpec.bind("memberId", memberId);
                        dataSpec  = dataSpec.bind("memberId",  memberId);
                    }
                    if (hasKw) {
                        String kw = "%" + keyword.trim() + "%";
                        countSpec = countSpec.bind("kw", kw);
                        dataSpec  = dataSpec.bind("kw",  kw);
                    }
                    countSpec = countSpec.bind("year", effectiveYear);
                    dataSpec  = dataSpec.bind("year",  effectiveYear);
                    if (hasDept) {
                        countSpec = countSpec.bind("department", department.trim());
                        dataSpec  = dataSpec.bind("department",  department.trim());
                    }
                    if (hasSt) {
                        countSpec = countSpec.bind("status", status.trim());
                        dataSpec  = dataSpec.bind("status",  status.trim());
                    }
                    dataSpec = dataSpec.bind("size", size).bind("offset", (long) index * size);

                    Mono<Long> countMono = countSpec
                            .map((row, meta) -> { Object v = row.get(0); return v instanceof Number n ? n.longValue() : 0L; })
                            .one().defaultIfEmpty(0L);

                    Flux<MaintenanceResponseDTO> dataFlux = dataSpec
                            .map((row, meta) -> MaintenanceResponseDTO.builder()
                                    .id(row.get("id", Long.class))
                                    .machineCode(row.get("machine_code", String.class))
                                    .machineName(row.get("machine_name", String.class))
                                    .years(row.get("years", String.class))
                                    .round(row.get("round", Integer.class))
                                    .dueDate(row.get("due_date", LocalDate.class))
                                    .planDate(row.get("plan_date", LocalDate.class))
                                    .startDate(row.get("start_date", LocalDate.class))
                                    .actualDate(row.get("actual_date", LocalDate.class))
                                    .status(row.get("status", String.class))
                                    .maintenanceBy(row.get("maintenance_by", String.class))
                                    .responsibleMaintenance(row.get("responsible_maintenance", Long.class))
                                    .responsibleMaintenanceName(row.get("responsible_maintenance_name", String.class))
                                    .machineDepartmentCode(row.get("machine_department_code", String.class))
                                    .machineDepartmentName(row.get("machine_department_name", String.class))
                                    .note(row.get("note", String.class))
                                    .attachment(row.get("attachment", String.class))
                                    .checklistRecordId(row.get("checklist_record_id", Long.class))
                                    .build())
                            .all();

                    return Mono.zip(countMono, dataFlux.collectList())
                            .map(tuple -> {
                                long total = tuple.getT1();
                                return PagedResponse.<MaintenanceResponseDTO>builder()
                                        .success(true).message("Success")
                                        .data(tuple.getT2())
                                        .totalElements(total)
                                        .totalPages((int) Math.ceil((double) total / size))
                                        .index(index).size(size).build();
                            });
                })
                .onErrorResume(e -> {
                    log.error("Failed to fetch maintenance page: {}", e.getMessage(), e);
                    return Mono.just(PagedResponse.<MaintenanceResponseDTO>builder()
                            .success(false).message(e.getMessage())
                            .data(List.of()).totalElements(0L).totalPages(0)
                            .index(index).size(size).build());
                });
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // FILTER OPTIONS — ใช้ roleFilterJoin (JOIN machine m อยู่แล้ว)
    // ═══════════════════════════════════════════════════════════════════════════

    public Mono<MaintenanceFilterOptionsDTO> getFilterOptions() {
        return ReactiveSecurityContextHolder.getContext()
                .mapNotNull(ctx -> (MemberPrincipal) Objects.requireNonNull(ctx.getAuthentication()).getPrincipal())
                .flatMap(principal -> {
                    String sql = """
                            SELECT DISTINCT
                                EXTRACT(YEAR FROM mr.due_date)::int        AS year,
                                m.department                               AS department_code,
                                COALESCE(d.department, m.department, '')   AS department_name,
                                d.division                                  AS division,
                                mr.status                                   AS status
                            FROM maintenance_record mr
                            LEFT JOIN machine m ON m.machine_code = mr.machine_code
                            LEFT JOIN department d ON d.department_code::text = m.department
                            WHERE mr.due_date IS NOT NULL
                            """ + roleFilterJoin(principal) + """
                            ORDER BY department_name ASC, division ASC
                            """;

                    return template.getDatabaseClient().sql(sql)
                            .map((row, meta) -> new Object[]{
                                    getIntValueNullable(row),
                                    row.get("department_code", String.class),
                                    row.get("department_name", String.class),
                                    row.get("division",         String.class),
                                    row.get("status",           String.class),
                            })
                            .all().collectList()
                            .map(rows -> {
                                Set<Integer>        years     = new TreeSet<>(Comparator.reverseOrder());
                                Map<String, String> depts     = new LinkedHashMap<>();
                                Set<String>         statusSet = new LinkedHashSet<>();
                                for (Object[] r : rows) {
                                    if (r[0] != null) years.add((Integer) r[0]);
                                    String dc  = (String) r[1];
                                    String dn  = (String) r[2];
                                    String div = (String) r[3];
                                    String label = StringUtils.hasText(div) ? dn + " - " + div : dn;
                                    if (StringUtils.hasText(dc))           depts.putIfAbsent(dc, label);
                                    if (StringUtils.hasText((String) r[4])) statusSet.add((String) r[4]);
                                }
                                List<MaintenanceFilterOptionsDTO.DepartmentOption> deptList = depts.entrySet().stream()
                                        .map(e -> MaintenanceFilterOptionsDTO.DepartmentOption.builder()
                                                .code(e.getKey()).name(e.getValue()).build())
                                        .toList();
                                return MaintenanceFilterOptionsDTO.builder()
                                        .years(new ArrayList<>(years))
                                        .departments(deptList)
                                        .statuses(new ArrayList<>(statusSet))
                                        .build();
                            });
                })
                .onErrorResume(e -> {
                    log.error("Failed to fetch maintenance filter options: {}", e.getMessage(), e);
                    return Mono.just(MaintenanceFilterOptionsDTO.builder()
                            .years(List.of()).departments(List.of()).statuses(List.of()).build());
                });
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // DEPARTMENT SUMMARY — ใช้ roleFilterExists (GROUP BY department)
    // ═══════════════════════════════════════════════════════════════════════════

    public Flux<MaintenanceDepartmentSummaryDTO> getDepartmentSummaryWithRole(Integer year) {
        return ReactiveSecurityContextHolder.getContext()
                .mapNotNull(ctx -> (MemberPrincipal) Objects.requireNonNull(ctx.getAuthentication()).getPrincipal())
                .flatMapMany(principal -> {
                    int effectiveYear = (year != null) ? year : LocalDate.now().getYear();

                    String sql = """
                        SELECT
                            department,
                            department_name,
                            COUNT(*)                                                            AS total,
                            COUNT(CASE WHEN status = 'Pass'     THEN 1 END)                    AS total_pass,
                            COUNT(CASE WHEN status = 'Not Pass' THEN 1 END)                    AS total_not_pass,
                            COUNT(CASE WHEN actual_date IS NOT NULL
                                       AND actual_date <= due_date THEN 1 END)                 AS total_on_time,
                            COUNT(CASE WHEN actual_date IS NOT NULL
                                       AND actual_date > due_date  THEN 1 END)                 AS total_overdue,
                            COUNT(CASE WHEN actual_date IS NOT NULL THEN 1 END)                AS total_completed,
                            COUNT(CASE WHEN actual_date IS NULL     THEN 1 END)                AS total_pending,
                            ROUND(COUNT(CASE WHEN status = 'Pass' THEN 1 END) * 100.0
                                / NULLIF(COUNT(*), 0), 2)                                      AS pass_rate,
                            ROUND(COUNT(CASE WHEN status = 'Not Pass' THEN 1 END) * 100.0
                                / NULLIF(COUNT(*), 0), 2)                                      AS not_pass_rate,
                            ROUND(COUNT(CASE WHEN actual_date IS NOT NULL
                                            AND actual_date <= due_date THEN 1 END) * 100.0
                                / NULLIF(COUNT(*), 0), 2)                                      AS on_time_rate,
                            ROUND(COUNT(CASE WHEN actual_date IS NOT NULL
                                            AND actual_date > due_date THEN 1 END) * 100.0
                                / NULLIF(COUNT(*), 0), 2)                                      AS overdue_rate,
                            ROUND(COUNT(CASE WHEN actual_date IS NOT NULL THEN 1 END) * 100.0
                                / NULLIF(COUNT(*), 0), 2)                                      AS completed_rate,
                            ROUND(COUNT(CASE WHEN actual_date IS NULL THEN 1 END) * 100.0
                                / NULLIF(COUNT(*), 0), 2)                                      AS pending_rate
                        FROM (
                            SELECT DISTINCT ON (mr.id)
                                mr.id,
                                mr.status,
                                mr.actual_date,
                                mr.due_date,
                                m.department AS department,
                                CASE
                                    WHEN d.department IS NOT NULL AND d.division IS NOT NULL AND d.division != ''
                                        THEN d.department || ' - ' || d.division
                                    WHEN d.department IS NOT NULL THEN d.department
                                    ELSE m.department
                                END AS department_name
                            FROM maintenance_record mr
                            JOIN machine m ON m.machine_code = mr.machine_code
                            LEFT JOIN department d ON d.department_code::text = m.department
                            WHERE (mr.is_canceled = FALSE OR mr.is_canceled IS NULL)
                              AND EXTRACT(YEAR FROM mr.due_date) = """ + effectiveYear + "\n                            "
                            + roleFilterJoin(principal) + """
                            ORDER BY mr.id
                        ) sub
                        GROUP BY department, department_name
                        HAVING COUNT(*) > 0
                        ORDER BY department_name ASC
                        """;

                    return template.getDatabaseClient().sql(sql)
                            .map((row, metadata) -> mapDepartmentSummary(row))
                            .all()
                            .onErrorResume(e -> {
                                log.error("Error fetching maintenance department summary with role", e);
                                return Flux.empty();
                            });
                });
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // MONTHLY PLAN-ACTUAL — ใช้ roleFilterExists
    // ═══════════════════════════════════════════════════════════════════════════

    public Flux<MaintenanceMonthlyDTO> getMonthlyPlanActualSummary(Integer year) {
        return ReactiveSecurityContextHolder.getContext()
                .mapNotNull(ctx -> (MemberPrincipal) Objects.requireNonNull(ctx.getAuthentication()).getPrincipal())
                .flatMapMany(principal -> {
                    String yearFilter = (year != null) ? "AND EXTRACT(YEAR FROM mr.due_date) = " + year + " " : "";
                    String roleFilter = roleFilterExists(principal);

                    String sql = """
                        SELECT
                            EXTRACT(YEAR  FROM mr.due_date)::int AS year,
                            EXTRACT(MONTH FROM mr.due_date)::int AS month,
                            mr.responsible_maintenance           AS member_id,
                            MAX(COALESCE(
                                NULLIF(TRIM(mb.first_name || ' ' || mb.last_name), ''),
                                mb.first_name, mb.user_name, 'Unassigned')) AS member_name,
                            COUNT(*)                             AS total_plan,
                            COUNT(CASE WHEN mr.actual_date IS NOT NULL
                                       AND mr.actual_date <= mr.due_date THEN 1 END) AS total_on_time,
                            COUNT(CASE WHEN (mr.actual_date IS NOT NULL AND mr.actual_date > mr.due_date)
                                        OR   mr.actual_date IS NULL THEN 1 END)      AS total_overdue
                        FROM maintenance_record mr
                        LEFT JOIN member mb ON mb.id = mr.responsible_maintenance
                        WHERE mr.due_date IS NOT NULL
                        """ + roleFilter + " " + yearFilter + """
                        GROUP BY
                            EXTRACT(YEAR  FROM mr.due_date),
                            EXTRACT(MONTH FROM mr.due_date),
                            mr.responsible_maintenance
                        ORDER BY year ASC, month ASC, member_name ASC
                        """;

                    return template.getDatabaseClient().sql(sql)
                            .map((row, meta) -> new Object[]{
                                    getIntValue(row, "year"), getIntValue(row, "month"),
                                    row.get("member_id", Long.class), row.get("member_name", String.class),
                                    getLongValue(row, "total_plan"),
                                    getLongValue(row, "total_on_time"),
                                    getLongValue(row, "total_overdue"),
                            })
                            .all().collectList()
                            .flatMapMany(flatRows -> {
                                LinkedHashMap<String, List<MaintenanceMonthlyDTO.ResponsibleSummary>> monthMap = new LinkedHashMap<>();
                                Map<String, long[]> monthTotals = new LinkedHashMap<>();
                                for (Object[] r : flatRows) {
                                    String key = r[0] + "-" + r[1];
                                    monthMap.computeIfAbsent(key, k -> new ArrayList<>())
                                            .add(MaintenanceMonthlyDTO.ResponsibleSummary.builder()
                                                    .memberId((Long) r[2]).memberName((String) r[3])
                                                    .totalPlan((long) r[4]).totalOnTime((long) r[5]).totalOverdue((long) r[6])
                                                    .build());
                                    monthTotals.merge(key, new long[]{ (long)r[4], (long)r[5], (long)r[6] },
                                            (a, b) -> new long[]{ a[0]+b[0], a[1]+b[1], a[2]+b[2] });
                                }
                                return Flux.fromIterable(monthMap.entrySet().stream().map(e -> {
                                    String[] p = e.getKey().split("-");
                                    long[]   t = monthTotals.get(e.getKey());
                                    return MaintenanceMonthlyDTO.builder()
                                            .year(Integer.parseInt(p[0])).month(Integer.parseInt(p[1]))
                                            .totalPlan(t[0]).totalOnTime(t[1]).totalOverdue(t[2])
                                            .byResponsible(e.getValue()).build();
                                }).toList());
                            })
                            .onErrorResume(e -> {
                                log.error("Error fetching monthly plan-actual summary", e);
                                return Flux.empty();
                            });
                });
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // CALENDAR
    // ADMIN  → ไม่ JOIN machine (ครบทุก record)
    // Others → JOIN machine + DISTINCT ON (r.id) + filter machine_status
    // ═══════════════════════════════════════════════════════════════════════════

    public Flux<MaintenanceResponseDTO> getCalendarEvents(int year, int month) {
        return ReactiveSecurityContextHolder.getContext()
                .mapNotNull(ctx -> (MemberPrincipal) Objects.requireNonNull(ctx.getAuthentication()).getPrincipal())
                .flatMapMany(principal -> {
                    final String sql;
                    if ("ADMIN".equals(principal.role())) {
                        sql = """
                            SELECT DISTINCT ON (r.id)
                                r.id, r.machine_code, r.machine_name, r.years, r.round, r.due_date, r.status,
                                NULL::text AS machine_department_code,
                                NULL::text AS machine_department_name
                            FROM maintenance_record r
                            WHERE (r.is_canceled = FALSE OR r.is_canceled IS NULL)
                              AND EXTRACT(YEAR  FROM r.due_date) = :year
                              AND EXTRACT(MONTH FROM r.due_date) = :month
                            ORDER BY r.id, r.due_date ASC
                            """;
                    } else {
                        String statusFilter = "AND m.machine_status IN ('OPERATIONAL', 'NON-OPERATIONAL', 'UNDER MAINTENANCE')";
                        String roleFilter   = switch (principal.role()) {
                            case "DEPARTMENT_ADMIN" -> principal.departmentId() != null
                                    ? "AND m.department LIKE (SELECT LEFT(d.department_code, LENGTH(d.department_code) - 1) || '%' FROM department d WHERE d.id = " + principal.departmentId() + ")"
                                    : "AND 1=0";
                            case "MANAGER"    -> "AND m.manager_id    = " + principal.memberId();
                            case "SUPERVISOR" -> "AND m.supervisor_id = " + principal.memberId();
                            default           -> "AND (m.responsible_person_id = " + principal.memberId()
                                    + " OR r.responsible_maintenance = " + principal.memberId() + ")";
                        };
                        sql = """
                            SELECT DISTINCT ON (r.id)
                                r.id, r.machine_code, r.machine_name, r.years, r.round, r.due_date, r.status,
                                m.department                             AS machine_department_code,
                                COALESCE(d.department, m.department, '') AS machine_department_name
                            FROM maintenance_record r
                            JOIN machine m ON m.machine_code = r.machine_code
                            LEFT JOIN department d ON d.department_code::text = m.department
                            WHERE (r.is_canceled = FALSE OR r.is_canceled IS NULL)
                              AND EXTRACT(YEAR  FROM r.due_date) = :year
                              AND EXTRACT(MONTH FROM r.due_date) = :month
                              """ + statusFilter + " " + roleFilter + """
                            ORDER BY r.id, r.due_date ASC
                            """;
                    }

                    return template.getDatabaseClient().sql(sql)
                            .bind("year", year).bind("month", month)
                            .map((row, meta) -> MaintenanceResponseDTO.builder()
                                    .id(row.get("id", Long.class))
                                    .machineCode(row.get("machine_code", String.class))
                                    .machineName(row.get("machine_name", String.class))
                                    .years(row.get("years", String.class))
                                    .round(row.get("round", Integer.class))
                                    .dueDate(row.get("due_date", LocalDate.class))
                                    .status(row.get("status", String.class))
                                    .machineDepartmentCode(row.get("machine_department_code", String.class))
                                    .machineDepartmentName(row.get("machine_department_name", String.class))
                                    .build())
                            .all()
                            .onErrorResume(e -> {
                                log.error("Failed to fetch maintenance calendar: {}", e.getMessage(), e);
                                return Flux.empty();
                            });
                });
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // GET BY ID / GET BY MACHINE CODE
    // ═══════════════════════════════════════════════════════════════════════════

    public Mono<ApiResponse<MaintenanceResponseDTO>> getById(Long id) {
        return template.selectOne(Query.query(Criteria.where("id").is(id)), MaintenanceRecord.class)
                .flatMap(maintenance -> {
                    List<Long> memberIds = new ArrayList<>();
                    if (maintenance.getCreatedBy() != null) memberIds.add(maintenance.getCreatedBy());
                    if (maintenance.getUpdatedBy()  != null) memberIds.add(maintenance.getUpdatedBy());
                    Mono<Map<Long, Member>> membersMono = memberIds.isEmpty()
                            ? Mono.just(new HashMap<>()) : commonService.fetchMembersByIds(memberIds);
                    return membersMono.map(m -> ApiResponse.success("MS017", MaintenanceResponseDTO.from(maintenance)));
                })
                .switchIfEmpty(Mono.just(ApiResponse.error("MS018", "Data not found")))
                .onErrorResume(e -> { log.error("Failed to fetch maintenance: {}", e.getMessage(), e); return Mono.just(ApiResponse.error("MS019", e.getMessage())); });
    }

    public Mono<ApiResponse<List<MaintenanceResponseDTO>>> getByMachineCode(String machineCode) {
        return template.select(
                        Query.query(Criteria.where("machine_code").is(machineCode)).sort(Sort.by("due_date").descending()),
                        MaintenanceRecord.class)
                .collectList()
                .flatMap(records -> {
                    if (records.isEmpty()) return Mono.just(ApiResponse.<List<MaintenanceResponseDTO>>error("MS018", "Data not found"));
                    return Mono.just(ApiResponse.success("MS017", records.stream().map(MaintenanceResponseDTO::from).toList()));
                })
                .onErrorResume(e -> { log.error("Failed to fetch maintenance by machine code: {}", e.getMessage(), e); return Mono.just(ApiResponse.error("MS019", e.getMessage())); });
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // VALIDATE
    // ═══════════════════════════════════════════════════════════════════════════

    public Mono<MaintenanceDTO> validateData(MaintenanceDTO dto, boolean isUpdate) {
        if (!isUpdate && dto.getDueDate() == null)
            return Mono.error(new ThrowException("MS001", "Maintenance due date is required"));
        return template.selectOne(Query.query(Criteria.where("id").is(dto.getId())), MaintenanceRecord.class)
                .switchIfEmpty(Mono.error(new ThrowException("MS018", "Maintenance record not found")))
                .flatMap(existing -> Mono.just(dto));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PRIVATE HELPERS
    // ═══════════════════════════════════════════════════════════════════════════

    private Update buildUpdateFromDTO(MaintenanceDTO dto) {
        Update update = Update.update("updated_at", java.time.LocalDateTime.now());
        if (dto.getAttachment()             != null) update = update.set("attachment",              dto.getAttachment());
        if (dto.getDueDate()                != null) update = update.set("due_date",                dto.getDueDate());
        if (dto.getPlanDate()               != null) update = update.set("plan_date",               dto.getPlanDate());
        if (dto.getStartDate()              != null) update = update.set("start_date",              dto.getStartDate());
        if (dto.getActualDate()             != null) update = update.set("actual_date",             dto.getActualDate());
        if (dto.getStatus()                 != null) update = update.set("status",                  dto.getStatus());
        if (dto.getMaintenanceBy()          != null) update = update.set("maintenance_by",          dto.getMaintenanceBy());
        if (dto.getNote()                   != null) update = update.set("note",                    dto.getNote());
        if (dto.getResponsibleMaintenance() != null) update = update.set("responsible_maintenance", dto.getResponsibleMaintenance());
        return update;
    }

    private MaintenanceDepartmentSummaryDTO mapDepartmentSummary(io.r2dbc.spi.Row row) {
        try {
            return MaintenanceDepartmentSummaryDTO.builder()
                    .department(row.get("department", String.class))
                    .departmentName(row.get("department_name", String.class))
                    .total(getLongValue(row, "total"))
                    .totalPass(getLongValue(row, "total_pass"))
                    .totalNotPass(getLongValue(row, "total_not_pass"))
                    .totalOnTime(getLongValue(row, "total_on_time"))
                    .totalOverdue(getLongValue(row, "total_overdue"))
                    .totalCompleted(getLongValue(row, "total_completed"))
                    .totalPending(getLongValue(row, "total_pending"))
                    .passRate(getDoubleValue(row, "pass_rate"))
                    .notPassRate(getDoubleValue(row, "not_pass_rate"))
                    .onTimeRate(getDoubleValue(row, "on_time_rate"))
                    .overdueRate(getDoubleValue(row, "overdue_rate"))
                    .completedRate(getDoubleValue(row, "completed_rate"))
                    .pendingRate(getDoubleValue(row, "pending_rate"))
                    .build();
        } catch (Exception e) {
            log.error("Error mapping maintenance department summary row", e);
            throw new RuntimeException("Error mapping maintenance department summary data", e);
        }
    }

    private Long getLongValue(io.r2dbc.spi.Row row, String col) {
        Object v = row.get(col);
        return switch (v) { case Long l -> l; case Number n -> n.longValue(); case null, default -> 0L; };
    }

    private Double getDoubleValue(io.r2dbc.spi.Row row, String col) {
        Object v = row.get(col);
        return switch (v) { case Double d -> d; case Number n -> n.doubleValue(); case null, default -> 0.0; };
    }

    private int getIntValue(io.r2dbc.spi.Row row, String col) {
        Object v = row.get(col);
        return switch (v) { case Integer i -> i; case Number n -> n.intValue(); case null, default -> 0; };
    }

    private Integer getIntValueNullable(Row row) {
        Object v = row.get("year");
        return switch (v) { case Integer i -> i; case Number n -> n.intValue(); case null, default -> null; };
    }
}