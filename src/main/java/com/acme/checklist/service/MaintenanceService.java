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
import org.springframework.r2dbc.core.DatabaseClient.GenericExecuteSpec;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.util.*;
import java.util.function.UnaryOperator;

@Slf4j
@Service
@RequiredArgsConstructor
public class MaintenanceService {

    private final R2dbcEntityTemplate template;
    private final CommonService       commonService;

    // ═══════════════════════════════════════════════════════════════════════════
    // SHARED SQL PIECES
    // ═══════════════════════════════════════════════════════════════════════════

    private static final String MEMBER_NAME_EXPR =
            "COALESCE(NULLIF(TRIM(mb.first_name || ' ' || mb.last_name), ''), mb.first_name, mb.user_name)";

    private static final String NOT_CANCELED = "(%s.is_canceled = FALSE OR %s.is_canceled IS NULL)";

    /**
     * Join department ให้ได้ 1 แถวต่อ machine เสมอ
     * (department_code เดียวอาจมีหลาย division ทำให้ record ซ้ำ)
     * ต้องวางต่อจาก "LEFT JOIN machine m"
     */
    private static final String DEPARTMENT_JOIN =
            "LEFT JOIN LATERAL (\n"
                    + "    SELECT dd.department, dd.division\n"
                    + "    FROM department dd\n"
                    + "    WHERE dd.department_code::text = m.department\n"
                    + "    ORDER BY dd.id\n"
                    + "    LIMIT 1\n"
                    + ") d ON TRUE\n";

    private static String notCanceled(String alias) {
        return String.format(NOT_CANCELED, alias, alias);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // MACHINE STATUS FILTER  (สวิตช์เดียว ใช้เหมือนกันทุกหน้า)
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * true  = ซ่อน record ของเครื่องที่ไม่ได้อยู่ในสถานะ ACTIVE_MACHINE_STATUSES ทุกหน้า
     *         (kanban, dashboard, monthly, calendar, filter options)
     * false = เห็นทุกสถานะทุกหน้า
     * record ที่ไม่มีเครื่องในตาราง machine จะแสดงเสมอ
     */
    private static final boolean HIDE_INACTIVE_MACHINES = false;

    private static final String ACTIVE_MACHINE_STATUSES = "'OPERATIONAL', 'UNDER MAINTENANCE'";

    /** label ของกลุ่ม record ที่ไม่มีเครื่อง หรือเครื่องไม่มีแผนก ใน department summary */
    private static final String UNASSIGNED_DEPARTMENT = "Unassigned";

    /** ใช้กับ query ที่ LEFT JOIN machine m แล้ว */
    private static String activeMachineFilter() {
        if (!HIDE_INACTIVE_MACHINES) return "";
        return "\nAND (m.machine_code IS NULL OR m.machine_status IN (" + ACTIVE_MACHINE_STATUSES + "))";
    }

    /** ใช้กับ query ที่ไม่ได้ join machine (alias record = "mr") */
    private static String activeMachineFilterExists() {
        if (!HIDE_INACTIVE_MACHINES) return "";
        return "\nAND NOT EXISTS (SELECT 1 FROM machine m3 WHERE m3.machine_code = mr.machine_code"
                + " AND (m3.machine_status IS NULL OR m3.machine_status NOT IN (" + ACTIVE_MACHINE_STATUSES + ")))";
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // ROLE FILTERS  (ที่เดียว ใช้ทุก endpoint)
    // ═══════════════════════════════════════════════════════════════════════════

    private static String deptPrefixSubquery(Long departmentId) {
        return "(SELECT LEFT(dp.department_code::text, LENGTH(dp.department_code::text) - 1) || '%'"
                + " FROM department dp WHERE dp.id = " + departmentId + ")";
    }

    /**
     * Role filter สำหรับ query ที่ LEFT JOIN machine m แล้ว
     *
     * @param rec alias ของ maintenance_record (เช่น "mr", "r")
     */
    private static String roleFilter(MemberPrincipal p, String rec) {
        if ("ADMIN".equals(p.role())) return "";
        if ("DEPARTMENT_ADMIN".equals(p.role())) {
            return p.departmentId() != null
                    ? "\nAND m.department LIKE " + deptPrefixSubquery(p.departmentId())
                    : "\nAND 1=0";
        }

        Long id = p.memberId();
        if (id == null) return "\nAND 1=0";
        String assigned = rec + ".responsible_maintenance = " + id;

        return switch (p.role()) {
            case "MANAGER"    -> "\nAND (m.manager_id = "            + id + " OR " + assigned + ")";
            case "SUPERVISOR" -> "\nAND (m.supervisor_id = "         + id + " OR " + assigned + ")";
            default           -> "\nAND (m.responsible_person_id = " + id + " OR " + assigned + ")";
        };
    }

    /** Role filter สำหรับ query ที่ไม่ได้ join machine (ใช้ EXISTS แทน) — alias record ต้องเป็น "mr" */
    private static String roleFilterExists(MemberPrincipal p) {
        if ("ADMIN".equals(p.role())) return "";
        String machineOf = "SELECT 1 FROM machine m2 WHERE m2.machine_code = mr.machine_code";

        if ("DEPARTMENT_ADMIN".equals(p.role())) {
            return p.departmentId() != null
                    ? "\nAND EXISTS (" + machineOf + " AND m2.department LIKE " + deptPrefixSubquery(p.departmentId()) + ")"
                    : "\nAND 1=0";
        }

        Long id = p.memberId();
        if (id == null) return "\nAND 1=0";
        String assigned = "mr.responsible_maintenance = " + id;

        return switch (p.role()) {
            case "MANAGER"    -> "\nAND (EXISTS (" + machineOf + " AND m2.manager_id = "            + id + ") OR " + assigned + ")";
            case "SUPERVISOR" -> "\nAND (EXISTS (" + machineOf + " AND m2.supervisor_id = "         + id + ") OR " + assigned + ")";
            default           -> "\nAND (EXISTS (" + machineOf + " AND m2.responsible_person_id = " + id + ") OR " + assigned + ")";
        };
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // MAINTENANCE-BY FILTER  (bind parameter — กัน SQL injection)
    // ═══════════════════════════════════════════════════════════════════════════

    private static boolean hasMb(String maintenanceBy) {
        return StringUtils.hasText(maintenanceBy) && !"ALL".equalsIgnoreCase(maintenanceBy.trim());
    }

    private static String mbClause(String maintenanceBy, String alias) {
        return hasMb(maintenanceBy) ? "\nAND " + alias + ".maintenance_by = :maintenanceBy" : "";
    }

    private static GenericExecuteSpec bindMb(GenericExecuteSpec spec, String maintenanceBy) {
        return hasMb(maintenanceBy) ? spec.bind("maintenanceBy", maintenanceBy.trim().toUpperCase()) : spec;
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

    /** ตรงกับ canEdit ฝั่งหน้าบ้าน: ADMIN หรือเป็นผู้รับผิดชอบ maintenance ของ record นั้น */
    private static boolean canEdit(MemberPrincipal p, MaintenanceRecord record) {
        return "ADMIN".equals(p.role())
                || (p.memberId() != null && Objects.equals(record.getResponsibleMaintenance(), p.memberId()));
    }

    /** ใช้ role filter ชุดเดียวกับหน้า list ตรวจว่ามองเห็น record นี้ได้หรือไม่ */
    private Mono<Boolean> canView(MemberPrincipal p, Long recordId) {
        if ("ADMIN".equals(p.role())) return Mono.just(true);
        String sql = "SELECT COUNT(*)\n"
                + "FROM maintenance_record mr\n"
                + "LEFT JOIN machine m ON m.machine_code = mr.machine_code\n"
                + "WHERE mr.id = :id"
                + roleFilter(p, "mr");
        return template.getDatabaseClient().sql(sql)
                .bind("id", recordId)
                .map((row, meta) -> row.get(0) instanceof Number n ? n.longValue() : 0L)
                .one()
                .map(c -> c > 0)
                .defaultIfEmpty(false);
    }

    /** id ของ record ในเครื่องนี้ที่ผู้ใช้มองเห็นได้ (null = ADMIN เห็นทั้งหมด) */
    private Mono<Set<Long>> visibleIdsByMachine(MemberPrincipal p, String machineCode) {
        if ("ADMIN".equals(p.role())) return Mono.justOrEmpty((Set<Long>) null);
        String sql = "SELECT mr.id\n"
                + "FROM maintenance_record mr\n"
                + "LEFT JOIN machine m ON m.machine_code = mr.machine_code\n"
                + "WHERE mr.machine_code = :machineCode"
                + roleFilter(p, "mr");
        return template.getDatabaseClient().sql(sql)
                .bind("machineCode", machineCode)
                .map((row, meta) -> row.get("id", Long.class))
                .all()
                .collect(HashSet::new, Set::add);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // UPDATE
    // ═══════════════════════════════════════════════════════════════════════════

    public Mono<ApiResponse<Void>> update(MaintenanceDTO dto) {
        return currentPrincipal()
                .switchIfEmpty(Mono.error(new ThrowException("MS401", "Unauthenticated")))
                .flatMap(p -> validateData(dto, true)
                        .flatMap(v -> template.selectOne(Query.query(Criteria.where("id").is(dto.getId())), MaintenanceRecord.class)
                                .flatMap(existing -> canEdit(p, existing)
                                        ? Mono.just(v)
                                        : Mono.error(new ThrowException("MS403", "No permission to edit this maintenance record")))))
                .flatMap(v -> commonService.update(dto.getId(), buildUpdateFromDTO(v), MaintenanceRecord.class)
                        .then(Mono.just(ApiResponse.<Void>success("MS001"))))
                .onErrorResume(e -> {
                    log.error("Failed to update maintenance: {}", e.getMessage());
                    return Mono.just(ApiResponse.error("MS001", e.getMessage()));
                });
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // GET PAGE
    // ═══════════════════════════════════════════════════════════════════════════

    public Mono<PagedResponse<MaintenanceResponseDTO>> getPage(
            String keyword, Integer year, String department, String status,
            String maintenanceBy, int index, int size) {

        return currentPrincipal()
                .flatMap(principal -> {
                    int     yr      = (year != null) ? year : LocalDate.now().getYear();
                    boolean hasKw   = StringUtils.hasText(keyword);
                    boolean hasDept = StringUtils.hasText(department);
                    boolean hasSt   = StringUtils.hasText(status);

                    String where = "\nWHERE " + notCanceled("mr")
                            + "\nAND mr.years::int = :year"
                            + roleFilter(principal, "mr")
                            + activeMachineFilter()
                            + (hasKw   ? "\nAND (mr.machine_code ILIKE :kw OR mr.machine_name ILIKE :kw)" : "")
                            + (hasDept ? "\nAND m.department = :department" : "")
                            + (hasSt   ? "\nAND mr.status = :status"        : "")
                            + mbClause(maintenanceBy, "mr");

                    String countSql =
                            "SELECT COUNT(*)\n"
                                    + "FROM maintenance_record mr\n"
                                    + "LEFT JOIN machine m ON m.machine_code = mr.machine_code"
                                    + where;

                    String dataSql =
                            "SELECT\n"
                                    + "    mr.id, mr.machine_code, mr.machine_name, mr.years, mr.round,\n"
                                    + "    mr.due_date, mr.plan_date, mr.start_date, mr.actual_date,\n"
                                    + "    mr.status, mr.maintenance_by, mr.responsible_maintenance,\n"
                                    + "    mr.note, mr.attachment, mr.checklist_record_id,\n"
                                    + "    " + MEMBER_NAME_EXPR + " AS responsible_maintenance_name,\n"
                                    + "    m.department AS machine_department_code,\n"
                                    + "    d.department AS machine_department_name\n"
                                    + "FROM maintenance_record mr\n"
                                    + "LEFT JOIN machine m ON m.machine_code = mr.machine_code\n"
                                    + DEPARTMENT_JOIN
                                    + "LEFT JOIN member mb ON mb.id = mr.responsible_maintenance"
                                    + where
                                    + "\nORDER BY m.department ASC NULLS LAST, mr.due_date ASC NULLS LAST, mr.id ASC"
                                    + "\nLIMIT :size OFFSET :offset";

                    UnaryOperator<GenericExecuteSpec> bindCommon = s -> {
                        s = s.bind("year", yr);
                        if (hasKw)   s = s.bind("kw", "%" + keyword.trim() + "%");
                        if (hasDept) s = s.bind("department", department.trim());
                        if (hasSt)   s = s.bind("status", status.trim());
                        return bindMb(s, maintenanceBy);
                    };

                    GenericExecuteSpec cs = bindCommon.apply(template.getDatabaseClient().sql(countSql));
                    GenericExecuteSpec ds = bindCommon.apply(template.getDatabaseClient().sql(dataSql))
                            .bind("size", size)
                            .bind("offset", (long) index * size);

                    Mono<Long> countMono = cs
                            .map((row, meta) -> row.get(0) instanceof Number n ? n.longValue() : 0L)
                            .one().defaultIfEmpty(0L);

                    Flux<MaintenanceResponseDTO> dataFlux = ds
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

                    return Mono.zip(countMono, dataFlux.collectList()).map(t -> {
                        long total = t.getT1();
                        return PagedResponse.<MaintenanceResponseDTO>builder()
                                .success(true).message("Success")
                                .data(t.getT2())
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
    // FILTER OPTIONS
    // ═══════════════════════════════════════════════════════════════════════════

    public Mono<MaintenanceFilterOptionsDTO> getFilterOptions() {
        return currentPrincipal()
                .flatMap(principal -> {
                    String sql =
                            "SELECT DISTINCT\n"
                                    + "    mr.years::int                            AS year,\n"
                                    + "    m.department                             AS department_code,\n"
                                    + "    COALESCE(d.department, m.department, '') AS department_name,\n"
                                    + "    d.division                               AS division,\n"
                                    + "    mr.status                                AS status,\n"
                                    + "    mr.maintenance_by                        AS maintenance_by\n"
                                    + "FROM maintenance_record mr\n"
                                    + "LEFT JOIN machine m ON m.machine_code = mr.machine_code\n"
                                    + DEPARTMENT_JOIN
                                    + "WHERE mr.years IS NOT NULL\n"
                                    + "AND " + notCanceled("mr")
                                    + roleFilter(principal, "mr")
                                    + activeMachineFilter()
                                    + "\nORDER BY department_name ASC, division ASC";

                    return template.getDatabaseClient().sql(sql)
                            .map((row, meta) -> new Object[]{
                                    getIntValueNullable(row),
                                    row.get("department_code", String.class),
                                    row.get("department_name", String.class),
                                    row.get("division",        String.class),
                                    row.get("status",          String.class),
                                    row.get("maintenance_by",  String.class),
                            })
                            .all().collectList()
                            .map(rows -> {
                                Set<Integer>        years    = new TreeSet<>(Comparator.reverseOrder());
                                Map<String, String> depts    = new LinkedHashMap<>();
                                Set<String>         statuses = new LinkedHashSet<>();
                                Set<String>         mbSet    = new LinkedHashSet<>();

                                for (Object[] r : rows) {
                                    if (r[0] != null) years.add((Integer) r[0]);
                                    String dc    = (String) r[1];
                                    String dn    = (String) r[2];
                                    String div   = (String) r[3];
                                    String label = StringUtils.hasText(div) ? dn + " - " + div : dn;
                                    if (StringUtils.hasText(dc))            depts.putIfAbsent(dc, label);
                                    if (StringUtils.hasText((String) r[4])) statuses.add((String) r[4]);
                                    if (StringUtils.hasText((String) r[5])) mbSet.add((String) r[5]);
                                }

                                List<MaintenanceFilterOptionsDTO.DepartmentOption> deptList = depts.entrySet().stream()
                                        .map(e -> MaintenanceFilterOptionsDTO.DepartmentOption.builder()
                                                .code(e.getKey()).name(e.getValue()).build())
                                        .toList();

                                return MaintenanceFilterOptionsDTO.builder()
                                        .years(new ArrayList<>(years))
                                        .departments(deptList)
                                        .statuses(new ArrayList<>(statuses))
                                        .maintenanceByOptions(new ArrayList<>(mbSet))
                                        .build();
                            });
                })
                .onErrorResume(e -> {
                    log.error("Failed to fetch maintenance filter options: {}", e.getMessage(), e);
                    return Mono.just(MaintenanceFilterOptionsDTO.builder()
                            .years(List.of()).departments(List.of())
                            .statuses(List.of()).maintenanceByOptions(List.of()).build());
                });
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // DEPARTMENT SUMMARY
    // ═══════════════════════════════════════════════════════════════════════════

    public Flux<MaintenanceDepartmentSummaryDTO> getDepartmentSummaryWithRole(Integer year, String maintenanceBy) {
        return currentPrincipal()
                .flatMapMany(principal -> {
                    int yr = (year != null) ? year : LocalDate.now().getYear();

                    String sql =
                            "SELECT\n"
                                    + "    department, department_name,\n"
                                    + "    COUNT(*)                                                            AS total,\n"
                                    + "    COUNT(CASE WHEN status = 'Pass'     THEN 1 END)                    AS total_pass,\n"
                                    + "    COUNT(CASE WHEN status = 'Not Pass' THEN 1 END)                    AS total_not_pass,\n"
                                    + "    COUNT(CASE WHEN actual_date IS NOT NULL AND actual_date <= due_date THEN 1 END) AS total_on_time,\n"
                                    + "    COUNT(CASE WHEN actual_date IS NOT NULL AND actual_date >  due_date THEN 1 END) AS total_overdue,\n"
                                    + "    COUNT(CASE WHEN actual_date IS NOT NULL THEN 1 END)                AS total_completed,\n"
                                    + "    COUNT(CASE WHEN actual_date IS NULL     THEN 1 END)                AS total_pending,\n"
                                    + "    ROUND(COUNT(CASE WHEN status = 'Pass'     THEN 1 END) * 100.0 / NULLIF(COUNT(*), 0), 2) AS pass_rate,\n"
                                    + "    ROUND(COUNT(CASE WHEN status = 'Not Pass' THEN 1 END) * 100.0 / NULLIF(COUNT(*), 0), 2) AS not_pass_rate,\n"
                                    + "    ROUND(COUNT(CASE WHEN actual_date IS NOT NULL AND actual_date <= due_date THEN 1 END) * 100.0 / NULLIF(COUNT(*), 0), 2) AS on_time_rate,\n"
                                    + "    ROUND(COUNT(CASE WHEN actual_date IS NOT NULL AND actual_date >  due_date THEN 1 END) * 100.0 / NULLIF(COUNT(*), 0), 2) AS overdue_rate,\n"
                                    + "    ROUND(COUNT(CASE WHEN actual_date IS NOT NULL THEN 1 END) * 100.0 / NULLIF(COUNT(*), 0), 2) AS completed_rate,\n"
                                    + "    ROUND(COUNT(CASE WHEN actual_date IS NULL     THEN 1 END) * 100.0 / NULLIF(COUNT(*), 0), 2) AS pending_rate\n"
                                    + "FROM (\n"
                                    + "    SELECT\n"
                                    + "        mr.id, mr.status, mr.actual_date, mr.due_date,\n"
                                    + "        m.department AS department,\n"
                                    + "        CASE\n"
                                    + "            WHEN d.department IS NOT NULL AND d.division IS NOT NULL AND d.division != ''\n"
                                    + "                THEN d.department || ' - ' || d.division\n"
                                    + "            WHEN d.department IS NOT NULL THEN d.department\n"
                                    + "            ELSE COALESCE(m.department, '" + UNASSIGNED_DEPARTMENT + "')\n"
                                    + "        END AS department_name\n"
                                    + "    FROM maintenance_record mr\n"
                                    + "    LEFT JOIN machine m ON m.machine_code = mr.machine_code\n"
                                    + DEPARTMENT_JOIN
                                    + "    WHERE " + notCanceled("mr") + "\n"
                                    + "      AND mr.years::int = :year"
                                    + mbClause(maintenanceBy, "mr")
                                    + roleFilter(principal, "mr")
                                    + activeMachineFilter()
                                    + "\n) sub\n"
                                    + "GROUP BY department, department_name\n"
                                    + "ORDER BY department_name ASC";

                    GenericExecuteSpec spec = template.getDatabaseClient().sql(sql).bind("year", yr);
                    return bindMb(spec, maintenanceBy)
                            .map((row, meta) -> mapDepartmentSummary(row))
                            .all()
                            .onErrorResume(e -> {
                                log.error("Error fetching maintenance department summary", e);
                                return Flux.empty();
                            });
                });
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // MONTHLY PLAN-ACTUAL
    // ═══════════════════════════════════════════════════════════════════════════

    public Flux<MaintenanceMonthlyDTO> getMonthlyPlanActualSummary(Integer year, String maintenanceBy) {
        return currentPrincipal()
                .flatMapMany(principal -> {
                    String sql =
                            "SELECT\n"
                                    + "    mr.years::int                        AS year,\n"
                                    + "    EXTRACT(MONTH FROM mr.due_date)::int AS month,\n"
                                    + "    mr.responsible_maintenance           AS member_id,\n"
                                    + "    MAX(" + MEMBER_NAME_EXPR + ")        AS member_name,\n"
                                    + "    COUNT(*) AS total_plan,\n"
                                    + "    COUNT(CASE WHEN mr.actual_date IS NOT NULL AND mr.actual_date <= mr.due_date THEN 1 END) AS total_on_time,\n"
                                    + "    COUNT(CASE WHEN (mr.actual_date IS NOT NULL AND mr.actual_date > mr.due_date) OR mr.actual_date IS NULL THEN 1 END) AS total_overdue\n"
                                    + "FROM maintenance_record mr\n"
                                    + "LEFT JOIN member mb ON mb.id = mr.responsible_maintenance\n"
                                    + "WHERE mr.years IS NOT NULL\n"
                                    + "AND " + notCanceled("mr")
                                    + roleFilterExists(principal)
                                    + activeMachineFilterExists()
                                    + (year != null ? "\nAND mr.years::int = :year" : "")
                                    + mbClause(maintenanceBy, "mr")
                                    + "\nGROUP BY mr.years::int, EXTRACT(MONTH FROM mr.due_date), mr.responsible_maintenance"
                                    + "\nORDER BY year ASC, month ASC, member_name ASC";

                    GenericExecuteSpec spec = template.getDatabaseClient().sql(sql);
                    if (year != null) spec = spec.bind("year", year);

                    return bindMb(spec, maintenanceBy)
                            .map((row, meta) -> new Object[]{
                                    getIntValue(row, "year"), getIntValue(row, "month"),
                                    row.get("member_id",   Long.class),
                                    row.get("member_name", String.class),
                                    getLongValue(row, "total_plan"),
                                    getLongValue(row, "total_on_time"),
                                    getLongValue(row, "total_overdue"),
                            })
                            .all().collectList()
                            .flatMapMany(flatRows -> {
                                LinkedHashMap<String, List<MaintenanceMonthlyDTO.ResponsibleSummary>> monthMap    = new LinkedHashMap<>();
                                Map<String, long[]>                                                   monthTotals = new LinkedHashMap<>();

                                for (Object[] r : flatRows) {
                                    String key = r[0] + "-" + r[1];
                                    monthMap.computeIfAbsent(key, k -> new ArrayList<>())
                                            .add(MaintenanceMonthlyDTO.ResponsibleSummary.builder()
                                                    .memberId((Long) r[2]).memberName((String) r[3])
                                                    .totalPlan((long) r[4]).totalOnTime((long) r[5]).totalOverdue((long) r[6])
                                                    .build());
                                    monthTotals.merge(key, new long[]{ (long) r[4], (long) r[5], (long) r[6] },
                                            (a, b) -> new long[]{ a[0] + b[0], a[1] + b[1], a[2] + b[2] });
                                }

                                return Flux.fromIterable(monthMap.entrySet().stream().map(e -> {
                                    String[] parts = e.getKey().split("-");
                                    long[]   t     = monthTotals.get(e.getKey());
                                    return MaintenanceMonthlyDTO.builder()
                                            .year(Integer.parseInt(parts[0])).month(Integer.parseInt(parts[1]))
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
    // CALENDAR  (query เดียวทุก role)
    // ═══════════════════════════════════════════════════════════════════════════

    public Flux<MaintenanceResponseDTO> getCalendarEvents(int year, int month, String maintenanceBy) {
        return currentPrincipal()
                .flatMapMany(principal -> {
                    String sql =
                            "SELECT\n"
                                    + "    r.id, r.machine_code, r.machine_name, r.years, r.round,\n"
                                    + "    r.due_date, r.status, r.maintenance_by, r.responsible_maintenance,\n"
                                    + "    " + MEMBER_NAME_EXPR + " AS responsible_maintenance_name,\n"
                                    + "    m.department                             AS machine_department_code,\n"
                                    + "    COALESCE(d.department, m.department, '') AS machine_department_name\n"
                                    + "FROM maintenance_record r\n"
                                    + "LEFT JOIN machine m ON m.machine_code = r.machine_code\n"
                                    + DEPARTMENT_JOIN
                                    + "LEFT JOIN member mb ON mb.id = r.responsible_maintenance\n"
                                    + "WHERE " + notCanceled("r") + "\n"
                                    + "  AND r.years::int = :year\n"
                                    + "  AND EXTRACT(MONTH FROM r.due_date)::int = :month"
                                    + activeMachineFilter()
                                    + roleFilter(principal, "r")
                                    + mbClause(maintenanceBy, "r")
                                    + "\nORDER BY r.due_date ASC, r.id ASC";

                    GenericExecuteSpec spec = template.getDatabaseClient().sql(sql)
                            .bind("year", year)
                            .bind("month", month);

                    return bindMb(spec, maintenanceBy)
                            .map((row, meta) -> MaintenanceResponseDTO.builder()
                                    .id(row.get("id", Long.class))
                                    .machineCode(row.get("machine_code", String.class))
                                    .machineName(row.get("machine_name", String.class))
                                    .years(row.get("years", String.class))
                                    .round(row.get("round", Integer.class))
                                    .dueDate(row.get("due_date", LocalDate.class))
                                    .status(row.get("status", String.class))
                                    .maintenanceBy(row.get("maintenance_by", String.class))
                                    .responsibleMaintenance(row.get("responsible_maintenance", Long.class))
                                    .responsibleMaintenanceName(row.get("responsible_maintenance_name", String.class))
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
    // GET BY ID / BY MACHINE CODE
    // ═══════════════════════════════════════════════════════════════════════════

    public Mono<ApiResponse<MaintenanceResponseDTO>> getById(Long id) {
        return currentPrincipal()
                .switchIfEmpty(Mono.error(new ThrowException("MS401", "Unauthenticated")))
                .flatMap(p -> canView(p, id))
                .flatMap(allowed -> !allowed
                        ? Mono.just(ApiResponse.<MaintenanceResponseDTO>error("MS018", "Data not found"))
                        : template.selectOne(Query.query(Criteria.where("id").is(id)), MaintenanceRecord.class)
                        .flatMap(m -> {
                            List<Long> memberIds = new ArrayList<>();
                            if (m.getCreatedBy()              != null) memberIds.add(m.getCreatedBy());
                            if (m.getUpdatedBy()              != null) memberIds.add(m.getUpdatedBy());
                            if (m.getResponsibleMaintenance() != null) memberIds.add(m.getResponsibleMaintenance());
                            Mono<Map<Long, Member>> membersMono = memberIds.isEmpty()
                                    ? Mono.just(new HashMap<>()) : commonService.fetchMembersByIds(memberIds);
                            return membersMono.map(mp -> {
                                Member responsible = m.getResponsibleMaintenance() != null
                                        ? mp.get(m.getResponsibleMaintenance()) : null;
                                return ApiResponse.success("MS017",
                                        MaintenanceResponseDTO.from(m, buildMemberName(responsible)));
                            });
                        })
                        .switchIfEmpty(Mono.just(ApiResponse.error("MS018", "Data not found"))))
                .onErrorResume(e -> {
                    log.error("Failed to fetch maintenance: {}", e.getMessage(), e);
                    return Mono.just(ApiResponse.error("MS019", e.getMessage()));
                });
    }

    public Mono<ApiResponse<List<MaintenanceResponseDTO>>> getByMachineCode(String machineCode) {
        return currentPrincipal()
                .switchIfEmpty(Mono.error(new ThrowException("MS401", "Unauthenticated")))
                .flatMap(p -> visibleIdsByMachine(p, machineCode)
                        .map(Optional::of)
                        .defaultIfEmpty(Optional.empty()))          // empty = ADMIN เห็นทั้งหมด
                .flatMap(visibleIds -> template.select(
                                Query.query(Criteria.where("machine_code").is(machineCode))
                                        .sort(Sort.by("due_date").descending()),
                                MaintenanceRecord.class)
                        .filter(r -> visibleIds.map(ids -> ids.contains(r.getId())).orElse(true))
                        .collectList())
                .flatMap(records -> {
                    if (records.isEmpty())
                        return Mono.just(ApiResponse.<List<MaintenanceResponseDTO>>error("MS018", "Data not found"));

                    Set<Long> memberIds = new HashSet<>();
                    records.stream()
                            .map(MaintenanceRecord::getResponsibleMaintenance)
                            .filter(Objects::nonNull)
                            .forEach(memberIds::add);

                    Mono<Map<Long, Member>> membersMono = memberIds.isEmpty()
                            ? Mono.just(new HashMap<>()) : commonService.fetchMembersByIds(new ArrayList<>(memberIds));

                    return membersMono.map(mp -> {
                        List<MaintenanceResponseDTO> dtos = records.stream().map(r -> {
                            Member responsible = r.getResponsibleMaintenance() != null
                                    ? mp.get(r.getResponsibleMaintenance()) : null;
                            return MaintenanceResponseDTO.from(r, buildMemberName(responsible));
                        }).toList();
                        return ApiResponse.success("MS017", dtos);
                    });
                })
                .onErrorResume(e -> {
                    log.error("Failed to fetch maintenance by machine code: {}", e.getMessage(), e);
                    return Mono.just(ApiResponse.error("MS019", e.getMessage()));
                });
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // VALIDATE
    // ═══════════════════════════════════════════════════════════════════════════

    public Mono<MaintenanceDTO> validateData(MaintenanceDTO dto, boolean isUpdate) {
        if (!isUpdate && dto.getDueDate() == null)
            return Mono.error(new ThrowException("MS001", "Maintenance due date is required"));
        return template.selectOne(Query.query(Criteria.where("id").is(dto.getId())), MaintenanceRecord.class)
                .switchIfEmpty(Mono.error(new ThrowException("MS018", "Maintenance record not found")))
                .map(existing -> dto);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PRIVATE HELPERS
    // ═══════════════════════════════════════════════════════════════════════════

    private static String buildMemberName(Member m) {
        if (m == null) return null;
        String full = ((m.getFirstName() != null ? m.getFirstName() : "") + " "
                + (m.getLastName()  != null ? m.getLastName()  : "")).trim();
        if (!full.isEmpty()) return full;
        if (m.getFirstName() != null) return m.getFirstName();
        return m.getUserName();
    }

    private Update buildUpdateFromDTO(MaintenanceDTO dto) {
        Update u = Update.update("updated_at", java.time.LocalDateTime.now());
        if (dto.getAttachment()             != null) u = u.set("attachment",              dto.getAttachment());
        if (dto.getDueDate()                != null) u = u.set("due_date",                dto.getDueDate());
        if (dto.getPlanDate()               != null) u = u.set("plan_date",               dto.getPlanDate());
        if (dto.getStartDate()              != null) u = u.set("start_date",              dto.getStartDate());
        if (dto.getActualDate()             != null) u = u.set("actual_date",             dto.getActualDate());
        if (dto.getStatus()                 != null) u = u.set("status",                  dto.getStatus());
        if (dto.getMaintenanceBy()          != null) u = u.set("maintenance_by",          dto.getMaintenanceBy());
        if (dto.getNote()                   != null) u = u.set("note",                    dto.getNote());
        if (dto.getResponsibleMaintenance() != null) u = u.set("responsible_maintenance", dto.getResponsibleMaintenance());
        return u;
    }

    private MaintenanceDepartmentSummaryDTO mapDepartmentSummary(Row row) {
        try {
            return MaintenanceDepartmentSummaryDTO.builder()
                    .department(row.get("department",           String.class))
                    .departmentName(row.get("department_name",  String.class))
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

    private Long getLongValue(Row row, String col) {
        Object v = row.get(col);
        return switch (v) { case Long l -> l; case Number n -> n.longValue(); case null, default -> 0L; };
    }

    private Double getDoubleValue(Row row, String col) {
        Object v = row.get(col);
        return switch (v) { case Double d -> d; case Number n -> n.doubleValue(); case null, default -> 0.0; };
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