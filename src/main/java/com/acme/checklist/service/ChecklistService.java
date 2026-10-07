package com.acme.checklist.service;

import com.acme.checklist.entity.enums.MachineStatus;
import com.acme.checklist.entity.*;
import com.acme.checklist.exception.ThrowException;
import com.acme.checklist.payload.ApiResponse;
import com.acme.checklist.payload.ListResponse;
import com.acme.checklist.payload.MemberPrincipal;
import com.acme.checklist.payload.PagedResponse;
import com.acme.checklist.payload.audit.AuditMemberDTO;
import com.acme.checklist.payload.checklist.ChecklistDTO;
import com.acme.checklist.payload.checklist.ChecklistListDTO;
import com.acme.checklist.payload.checklist.ChecklistResponseDTO;
import com.acme.checklist.payload.checklist.ChecklistStatsDTO;
import com.acme.checklist.payload.file.FileUploadDTO;
import com.acme.checklist.payload.member.MemberDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.data.relational.core.query.Criteria;
import org.springframework.data.relational.core.query.Query;
import org.springframework.data.relational.core.query.Update;
import org.springframework.data.relational.core.sql.SqlIdentifier;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

// ─── ChecklistService.java ────────────────────────────────────────────────────

@Slf4j
@Service
@RequiredArgsConstructor
public class ChecklistService {

    private final R2dbcEntityTemplate template;
    private final CommonService commonService;
    private final ObjectMapper objectMapper;
    private final FileStorageService fileStorageService;
    private final KpiService kpiService;

    private static final ZoneId ZONE = ZoneId.of("Asia/Bangkok");

    // =========================================================================
    //  CREATE
    // =========================================================================

    public Mono<ApiResponse<Void>> create(String requestJson, FilePart file) {
        log.info("[CHECKLIST] create — requestJson: {}", requestJson);

        ChecklistDTO dto;
        try {
            dto = objectMapper.readValue(requestJson, ChecklistDTO.class);
        } catch (Exception e) {
            log.error("[CHECKLIST] Parse error: {}", e.getMessage());
            return Mono.just(ApiResponse.error("MS002", "Invalid request format"));
        }

        Mono<String> imageNameMono = (file != null && !file.filename().isEmpty())
                ? fileStorageService.uploadFile(file, dto.getUserName()).map(FileUploadDTO::getFileName)
                : Mono.just("");

        ChecklistDTO finalDto = dto;
        return imageNameMono.flatMap(imageName -> {
            if (!imageName.isEmpty()) finalDto.setImage(imageName);
            return validateData(finalDto).flatMap(this::processSave);
        }).onErrorResume(e -> {
            log.error("[CHECKLIST] Failed to create: {}", e.getMessage(), e);
            return Mono.just(ApiResponse.error("MS002", e.getMessage()));
        });
    }

    // ─── PROCESS SAVE ─────────────────────────────────────────────────────────

    private Mono<ApiResponse<Void>> processSave(ChecklistDTO dto) {
        dto.setCheckType("GENERAL");

        Mono<Machine> machineMono = (dto.getMachineId() != null)
                ? template.selectOne(
                Query.query(Criteria.where("id").is(dto.getMachineId())
                        .and("machine_status").in(MachineStatus.activeDbValues())),
                Machine.class)
                : template.selectOne(
                Query.query(Criteria.where("machine_code").is(dto.getMachineCode())
                        .and("machine_status").in(MachineStatus.activeDbValues())),
                Machine.class);

        return machineMono
                .switchIfEmpty(Mono.error(new RuntimeException("Machine not found or inactive: "
                        + (dto.getMachineId() != null ? dto.getMachineId() : dto.getMachineCode()))))
                .flatMap(machine -> {
                    dto.setMachineId(machine.getId());

                    return ReactiveSecurityContextHolder.getContext()
                            .mapNotNull(ctx -> (MemberPrincipal) Objects.requireNonNull(ctx.getAuthentication()).getPrincipal())
                            .flatMap(principal -> {
                                LocalDate today       = LocalDate.now(ZONE);
                                boolean isWeekend     = today.getDayOfWeek() == DayOfWeek.SATURDAY
                                        || today.getDayOfWeek() == DayOfWeek.SUNDAY;
                                boolean isResponsible = Objects.equals(principal.memberId(), machine.getResponsiblePersonId());
                                boolean isPending     = "PENDING".equals(machine.getCheckStatus());

                                log.info("[CHECKLIST] isWeekend={} isResponsible={} isPending={}", isWeekend, isResponsible, isPending);
                                log.info("[CHECKLIST] principal.memberId={} machine.responsiblePersonId={}",
                                        principal.memberId(), machine.getResponsiblePersonId());
                                log.info("[CHECKLIST] machine.checkStatus={}", machine.getCheckStatus());

                                ChecklistRecord record = buildFromDTO(dto);

                                if (isResponsible && isPending && !isWeekend) {
                                    return saveAsResponsiblePending(dto, machine, record, principal.memberId());
                                } else {
                                    return saveAsCompleted(dto, machine, record, isResponsible);
                                }
                            });
                });
    }

    private Mono<ApiResponse<Void>> saveAsResponsiblePending(
            ChecklistDTO dto, Machine machine, ChecklistRecord record, Long memberId) {

        record.setChecklistStatus(
                machine.getSupervisorId() != null ? "PENDING SUPERVISOR" : "PENDING MANAGER"
        );
        record.setRecheck(true);

        List<Long> checklistIds = parseChecklistIds(dto.getMachineChecklist());

        Mono<Void> updateChecklistItems = template.select(
                        Query.query(Criteria.where("id").in(checklistIds)
                                .and("reset_time").not("0 0 0 * * 1")),
                        MachineChecklist.class)
                .map(MachineChecklist::getId)
                .collectList()
                .flatMap(filteredIds -> {
                    if (filteredIds.isEmpty()) return Mono.<Void>empty();
                    return Flux.fromIterable(filteredIds)
                            .flatMap(id -> template.update(MachineChecklist.class)
                                    .matching(Query.query(Criteria.where("id").is(id)))
                                    .apply(Update.update("check_status", true)))
                            .then();
                });

        Mono<Void> updateMachine = template.update(Machine.class)
                .matching(Query.query(Criteria.where("id").is(machine.getId())))
                .apply(Update.update("check_status", record.getChecklistStatus())
                        .set("machine_status", dto.getMachineStatus()))
                .then();

        return updateChecklistItems
                .then(commonService.save(record, ChecklistRecord.class))
                .flatMap(saved ->
                        updateMachine
                                .then(kpiService.recalculateKpiForPerson(memberId))
                                .then(Mono.just(ApiResponse.<Void>success("MS001")))
                );
    }

    private Mono<ApiResponse<Void>> saveAsCompleted(
            ChecklistDTO dto, Machine machine, ChecklistRecord record, boolean isResponsible) {

        record.setChecklistStatus("COMPLETED");
        record.setRecheck(false);

        Mono<Void> updateMachine;
        if (!isResponsible) {
            updateMachine = template.update(Machine.class)
                    .matching(Query.query(Criteria.where("id").is(machine.getId())))
                    .apply(Update.update("machine_status", dto.getMachineStatus()))
                    .then();
        } else {
            updateMachine = template.update(Machine.class)
                    .matching(Query.query(Criteria.where("id").is(machine.getId())))
                    .apply(Update.update("machine_status", dto.getMachineStatus())
                            .set("check_status", record.getChecklistStatus()))
                    .then();
        }

        return commonService.save(record, ChecklistRecord.class)
                .then(updateMachine)
                .then(Mono.just(ApiResponse.<Void>success("MS001")));
    }

    // =========================================================================
    //  UPDATE (Supervisor / Manager อนุมัติ)
    // =========================================================================

    public Mono<ApiResponse<Void>> update(ChecklistDTO checklistDTO) {
        return template.selectOne(
                        Query.query(Criteria.where("id").is(checklistDTO.getId())),
                        ChecklistRecord.class)
                .switchIfEmpty(Mono.error(new ThrowException("MS018")))
                .flatMap(existing -> {
                    checklistDTO.setSupervisor(existing.getSupervisor());
                    checklistDTO.setManager(existing.getManager());
                    checklistDTO.setChecklistStatus(existing.getChecklistStatus());
                    checklistDTO.setMachineStatus(existing.getMachineStatus());
                    return validateDataUpdate(checklistDTO);
                })
                .flatMap(validated -> {
                    Update update = buildUpdateFromDTO(validated);
                    return commonService.update(validated.getId(), update, ChecklistRecord.class)
                            .then(Mono.just(ApiResponse.<Void>success("RG")));
                })
                .onErrorResume(e -> {
                    log.error("[CHECKLIST] Failed to update: {}", e.getMessage());
                    return Mono.just(ApiResponse.error("MS004", e.getMessage()));
                });
    }

    // =========================================================================
    //  DELETE
    // =========================================================================

    public Mono<ApiResponse<Void>> delete(List<Long> ids) {
        return commonService.auditContext()
                .flatMap(ctx -> {
                    Long memberId     = ctx.get("X-Member-Id");
                    Long departmentId = ctx.get("X-Department-Id");
                    return commonService.deleteEntitiesByIds(
                            ids, ChecklistRecord.class,
                            "MS005", "MS006", "MS007",
                            ChecklistRecord::getMachineCode,
                            names -> postDeleteTask(memberId, departmentId));
                });
    }

    // =========================================================================
    //  GET PAGE
    // =========================================================================

    public Mono<PagedResponse<ChecklistListDTO>> getAllWithPage(String keyword, int index, int size) {
        Criteria criteria = Criteria.empty();
        if (StringUtils.hasText(keyword)) {
            criteria = Criteria.where("machine_name").like("%" + keyword + "%").ignoreCase(true)
                    .or("machine_code").like("%" + keyword + "%").ignoreCase(true);
        }
        Query query = Query.query(criteria).with(commonService.pageable(index, size, "created_at"));
        return commonService.executePagedQuery(index, size, query, criteria, ChecklistRecord.class, this::convertChecklistListDTOs);
    }

    public Mono<PagedResponse<ChecklistListDTO>> getPersonalWithPage(Long userId, String keyword, int index, int size) {
        Criteria criteria = Criteria.where("created_by").is(userId);
        if (StringUtils.hasText(keyword)) {
            criteria = criteria.and(
                    Criteria.where("machine_name").like("%" + keyword + "%").ignoreCase(true)
                            .or("machine_code").like("%" + keyword + "%").ignoreCase(true));
        }
        Query query = Query.query(criteria).with(commonService.pageable(index, size, "created_at"));
        return commonService.executePagedQuery(index, size, query, criteria, ChecklistRecord.class, this::convertChecklistListDTOs);
    }

    // =========================================================================
    //  GET BY ID
    // =========================================================================

    public Mono<ApiResponse<ChecklistResponseDTO>> getById(Long id) {
        return template.selectOne(Query.query(Criteria.where("id").is(id)), ChecklistRecord.class)
                .flatMap(record -> {
                    List<Long> auditIds = new ArrayList<>();
                    if (record.getCreatedBy() != null) auditIds.add(record.getCreatedBy());
                    if (record.getUpdatedBy() != null) auditIds.add(record.getUpdatedBy());

                    Mono<Map<Long, Member>> auditMembersMono = auditIds.isEmpty()
                            ? Mono.just(new HashMap<>())
                            : commonService.fetchMembersByIds(auditIds);

                    Mono<MemberDTO> supervisorMono = (record.getSupervisor() != null)
                            ? template.selectOne(
                                    Query.query(Criteria.where("id").is(record.getSupervisor())),
                                    Member.class)
                            .map(this::toMemberDTO)
                            .defaultIfEmpty(new MemberDTO())
                            : Mono.just(new MemberDTO());

                    Mono<MemberDTO> managerMono = (record.getManager() != null)
                            ? template.selectOne(
                                    Query.query(Criteria.where("id").is(record.getManager())),
                                    Member.class)
                            .map(this::toMemberDTO)
                            .defaultIfEmpty(new MemberDTO())
                            : Mono.just(new MemberDTO());

                    return Mono.zip(auditMembersMono, supervisorMono, managerMono)
                            .map(tuple -> {
                                Map<Long, Member> auditMap  = tuple.getT1();
                                MemberDTO         supervisor = tuple.getT2();
                                MemberDTO         manager    = tuple.getT3();

                                AuditMemberDTO createdByDTO = record.getCreatedBy() != null
                                        && auditMap.get(record.getCreatedBy()) != null
                                        ? AuditMemberDTO.from(auditMap.get(record.getCreatedBy())) : null;
                                AuditMemberDTO updatedByDTO = record.getUpdatedBy() != null
                                        && auditMap.get(record.getUpdatedBy()) != null
                                        ? AuditMemberDTO.from(auditMap.get(record.getUpdatedBy())) : null;

                                MemberDTO supervisorDTO = supervisor.getId() != null ? supervisor : null;
                                MemberDTO managerDTO    = manager.getId()    != null ? manager    : null;

                                return ApiResponse.success("MS017",
                                        ChecklistResponseDTO.from(record, createdByDTO, updatedByDTO, supervisorDTO, managerDTO));
                            });
                })
                .switchIfEmpty(Mono.just(ApiResponse.error("MS018", "Checklist not found")))
                .onErrorResume(e -> {
                    log.error("[CHECKLIST] Failed to fetch by id: {}", e.getMessage(), e);
                    return Mono.just(ApiResponse.error("MS019", e.getMessage()));
                });
    }

    private MemberDTO toMemberDTO(Member m) {
        MemberDTO dto = new MemberDTO();
        dto.setId(m.getId());
        dto.setEmployeeId(m.getEmployeeId());
        dto.setFirstName(m.getFirstName());
        dto.setLastName(m.getLastName());
        dto.setEmail(m.getEmail());
        dto.setRoleType(m.getRoleType());
        dto.setStatus(m.getStatus());
        return dto;
    }

    // =========================================================================
    //  GET WITH ROLE
    //
    //  DEPARTMENT_ADMIN → เห็น checklist ทุก machine ที่อยู่ใน department ตัวเอง
    //  โดย JOIN machine m → filter ด้วย department_code (pattern เดียวกับ DashboardService)
    // =========================================================================

    public Mono<PagedResponse<ChecklistListDTO>> getWithRole(String keyword, int index, int size) {
        return ReactiveSecurityContextHolder.getContext()
                .mapNotNull(ctx -> (MemberPrincipal) Objects.requireNonNull(ctx.getAuthentication()).getPrincipal())
                .flatMap(principal -> {
                    String role       = principal.role();
                    Long   memberId   = principal.memberId();
                    Long   deptId     = principal.departmentId();
                    log.info("[CHECKLIST] getWithRole — role={} memberId={} deptId={}", role, memberId, deptId);

                    // ── ADMIN: เห็นทุกอย่าง ────────────────────────────────────
                    if ("ADMIN".equals(role)) {
                        Criteria criteria = buildKeywordCriteria(keyword);
                        Query query = Query.query(criteria)
                                .with(commonService.pageable(index, size, "created_at"));
                        return commonService.executePagedQuery(
                                index, size, query, criteria,
                                ChecklistRecord.class, this::convertChecklistListDTOs);
                    }

                    // ── DEPARTMENT_ADMIN: เห็น checklist ทุก machine ใน department ─
                    if ("DEPARTMENT_ADMIN".equals(role)) {
                        if (deptId == null) {
                            log.warn("[CHECKLIST] DEPARTMENT_ADMIN has no departmentId, returning empty");
                            return Mono.just(PagedResponse.<ChecklistListDTO>builder()
                                    .success(true).message("Success")
                                    .data(List.of()).totalElements(0L).totalPages(0)
                                    .index(index).size(size).build());
                        }

                        // หา machine_codes ทั้งหมดใน department เดียวกัน
                        // ไม่มี JOIN → ใช้ชื่อ column ตรงๆ ไม่ใช้ alias "m"
                        String deptFilter = """
                                department LIKE (
                                    SELECT LEFT(d.department_code, LENGTH(d.department_code) - 1) || '%'
                                    FROM department d WHERE d.id = \s""" + deptId + ")";

                        return template.getDatabaseClient()
                                .sql("SELECT machine_code FROM machine WHERE " + deptFilter)
                                .map((row, meta) -> row.get("machine_code", String.class))
                                .all()
                                .collectList()
                                .flatMap(machineCodes -> {
                                    log.info("[CHECKLIST] DEPARTMENT_ADMIN machineCodes count={}", machineCodes.size());

                                    if (machineCodes.isEmpty()) {
                                        return Mono.just(PagedResponse.<ChecklistListDTO>builder()
                                                .success(true).message("Success")
                                                .data(List.of()).totalElements(0L).totalPages(0)
                                                .index(index).size(size).build());
                                    }

                                    Criteria criteria = Criteria.where("machine_code").in(machineCodes);
                                    if (StringUtils.hasText(keyword)) {
                                        criteria = criteria.and(
                                                Criteria.where("machine_name").like("%" + keyword + "%").ignoreCase(true)
                                                        .or("machine_code").like("%" + keyword + "%").ignoreCase(true));
                                    }

                                    Query query = Query.query(criteria)
                                            .with(commonService.pageable(index, size, "created_at"));
                                    return commonService.executePagedQuery(
                                            index, size, query, criteria,
                                            ChecklistRecord.class, this::convertChecklistListDTOs);
                                });
                    }

                    // ── MEMBER / SUPERVISOR / MANAGER: เห็นของตัวเอง + ที่รับผิดชอบ ──
                    return template.select(
                                    Query.query(Criteria.where("responsible_person_id").is(memberId)),
                                    Machine.class)
                            .map(Machine::getMachineCode)
                            .collectList()
                            .flatMap(machineCodes -> {
                                log.info("[CHECKLIST] getWithRole machineCodes={}", machineCodes);

                                Criteria baseCriteria = Criteria.where("created_by").is(memberId)
                                        .or("supervisor").is(memberId)
                                        .or("manager").is(memberId);

                                if (!machineCodes.isEmpty()) {
                                    baseCriteria = baseCriteria.or("machine_code").in(machineCodes);
                                }

                                Criteria criteria;
                                if (StringUtils.hasText(keyword)) {
                                    Criteria keywordCriteria = Criteria.where("machine_name")
                                            .like("%" + keyword + "%").ignoreCase(true)
                                            .or("machine_code").like("%" + keyword + "%").ignoreCase(true);
                                    criteria = baseCriteria.and(keywordCriteria);
                                } else {
                                    criteria = baseCriteria;
                                }

                                Query query = Query.query(criteria)
                                        .with(commonService.pageable(index, size, "created_at"));
                                return commonService.executePagedQuery(
                                        index, size, query, criteria,
                                        ChecklistRecord.class, this::convertChecklistListDTOs);
                            });
                });
    }

    // =========================================================================
    //  GET PENDING APPROVALS
    // =========================================================================

    public Mono<ListResponse<List<ChecklistListDTO>>> getPendingApprovals() {
        return ReactiveSecurityContextHolder.getContext()
                .mapNotNull(ctx -> (MemberPrincipal) Objects.requireNonNull(ctx.getAuthentication()).getPrincipal())
                .flatMap(principal -> {
                    Long   memberId = principal.memberId();
                    String role     = principal.role();

                    log.info("[CHECKLIST] getPendingApprovals — role={} memberId={}", role, memberId);

                    if (!role.equals("SUPERVISOR") && !role.equals("MANAGER")) {
                        return Mono.just(ListResponse.success("MS022", false, List.<ChecklistListDTO>of()));
                    }

                    Criteria criteria = Criteria
                            .where("checklist_status").is("PENDING SUPERVISOR").and("supervisor").is(memberId)
                            .or(Criteria.where("checklist_status").is("PENDING MANAGER").and("manager").is(memberId));

                    Query query = Query.query(criteria)
                            .with(commonService.pageable(0, 100, "created_at"));

                    return template.select(query, ChecklistRecord.class)
                            .map(ChecklistListDTO::from)
                            .collectList()
                            .doOnNext(list -> log.info("[CHECKLIST] getPendingApprovals found={} items", list.size()))
                            .map(list -> ListResponse.success("MS022", false, list));
                })
                .onErrorResume(e -> {
                    log.error("[CHECKLIST] Failed to fetch pending approvals: {}", e.getMessage(), e);
                    return Mono.just(ListResponse.error("MS022"));
                });
    }

    // =========================================================================
    //  STATS
    //
    //  ตรรกะเดียวกับ KpiService.recalculateKpiForPerson และ KpiService.roleFilter
    //  - รอบเดือน KPI: จันทร์ของสัปดาห์ที่มีศุกร์แรก ถึง ศุกร์สุดท้าย (Asia/Bangkok)
    //  - ประชากร: แถวในตาราง kpi ที่ผู้ใช้มองเห็นตาม role
    //  - แผนก: kpi.member_id → member.department_id → department.department
    // =========================================================================

    private static final String STATS_SQL = """
        WITH kp AS (
            SELECT
                k.member_id,
                k.months,
                k.check_all,
                k.checked,
                COALESCE(dep.department, 'UNASSIGNED') AS department
            FROM kpi k
            LEFT JOIN member mb ON mb.id = k.member_id
            LEFT JOIN LATERAL (
                SELECT d.department
                FROM department d
                WHERE d.department_code = mb.department_id
                LIMIT 1
            ) dep ON true
            WHERE k.years = :year
              AND (CAST(:deptName AS text) IS NULL OR dep.department = :deptName)
              AND (
                    :role = 'ADMIN'
                 OR (:role = 'DEPARTMENT_ADMIN' AND mb.department_id IN (
                        SELECT d2.department_code FROM department d2
                        WHERE d2.department = (
                            SELECT d1.department FROM department d1 WHERE d1.id = :deptId
                        )
                    ))
                 OR (:role = 'MANAGER'    AND (k.member_id = :memberId OR k.manager_id    = :memberId))
                 OR (:role = 'SUPERVISOR' AND (k.member_id = :memberId OR k.supervisor_id = :memberId))
                 OR (:role NOT IN ('ADMIN', 'DEPARTMENT_ADMIN', 'MANAGER', 'SUPERVISOR')
                     AND k.member_id = :memberId)
              )
        ),
        rec AS (
            SELECT DISTINCT ON (cr.id)
                cr.id,
                cr.checklist_status,
                kp.department,
                (
                    COALESCE(cr.machine_note, '') = 'Automatic recording'
                    AND UPPER(COALESCE(cr.reason_not_checked, ''))
                        IN ('NO ACTION TAKEN', 'RESPONSIBLE PERSON DID NOT PERFORM')
                ) AS is_auto,
                (
                    cr.recheck = true
                    AND cr.created_by = rh.responsible_person_id
                    AND NOT (
                        COALESCE(cr.machine_note, '') = 'Automatic recording'
                        AND UPPER(COALESCE(cr.reason_not_checked, ''))
                            IN ('NO ACTION TAKEN', 'RESPONSIBLE PERSON DID NOT PERFORM')
                    )
                ) AS counts_for_kpi,
                to_char(w.kpi_friday, 'MM') AS kpi_month
            FROM checklist_record cr
            CROSS JOIN LATERAL (
                SELECT (cr.created_at AT TIME ZONE 'Asia/Bangkok')::date AS local_date
            ) ld
            CROSS JOIN LATERAL (
                SELECT ld.local_date + (5 - EXTRACT(ISODOW FROM ld.local_date)::int) AS kpi_friday
            ) w
            JOIN machine m
                 ON m.machine_code = cr.machine_code
                AND m.machine_status = ANY(:activeStatuses)
            JOIN responsible_history rh
                 ON rh.machine_code = cr.machine_code
                AND ld.local_date >= rh.effective_from
                AND (rh.effective_to IS NULL OR ld.local_date <= rh.effective_to)
            JOIN kp
                 ON kp.member_id = rh.responsible_person_id
                AND kp.months    = to_char(w.kpi_friday, 'MM')
            WHERE cr.check_type = 'GENERAL'
              AND cr.created_at >= :fromTs
              AND cr.created_at <  :toTs
              AND to_char(w.kpi_friday, 'YYYY') = :year
              AND NOT (
                  ld.local_date > w.kpi_friday
                  AND EXTRACT(MONTH FROM w.kpi_friday + 7) <> EXTRACT(MONTH FROM w.kpi_friday)
              )
            ORDER BY cr.id, (cr.created_by = rh.responsible_person_id) DESC
        ),
        agg AS (
            SELECT
                department,
                kpi_month,
                COUNT(*)                                                                           AS daily_use,
                COUNT(*) FILTER (WHERE counts_for_kpi)                                             AS checked,
                COUNT(*) FILTER (WHERE counts_for_kpi AND checklist_status = 'COMPLETED')          AS approved,
                COUNT(*) FILTER (WHERE counts_for_kpi AND checklist_status = 'PENDING SUPERVISOR') AS wait_supervisor,
                COUNT(*) FILTER (WHERE counts_for_kpi AND checklist_status = 'PENDING MANAGER')    AS wait_manager,
                COUNT(*) FILTER (WHERE is_auto)                                                    AS auto_not_performed,
                COUNT(*) FILTER (WHERE NOT counts_for_kpi AND NOT is_auto)                         AS off_cycle
            FROM rec
            GROUP BY department, kpi_month
        ),
        tgt AS (
            SELECT
                department,
                months,
                COUNT(*)                    AS members,
                COALESCE(SUM(check_all), 0) AS check_all,
                COALESCE(SUM(checked),   0) AS kpi_checked
            FROM kp
            GROUP BY department, months
        ),
        grid AS (
            SELECT dep.department, gs.m AS month, lpad(gs.m::text, 2, '0') AS mm
            FROM (SELECT DISTINCT department FROM kp) dep
            CROSS JOIN generate_series(1, 12) AS gs(m)
        ),
        joined AS (
            SELECT
                g.department,
                g.month,
                COALESCE(t.members,            0) AS members,
                COALESCE(t.check_all,          0) AS check_all,
                COALESCE(t.kpi_checked,        0) AS kpi_checked,
                COALESCE(a.checked,            0) AS checked,
                COALESCE(a.approved,           0) AS approved,
                COALESCE(a.wait_supervisor,    0) AS wait_supervisor,
                COALESCE(a.wait_manager,       0) AS wait_manager,
                COALESCE(a.auto_not_performed, 0) AS auto_not_performed,
                COALESCE(a.off_cycle,          0) AS off_cycle,
                COALESCE(a.daily_use,          0) AS daily_use
            FROM grid g
            LEFT JOIN tgt t ON t.department = g.department AND t.months    = g.mm
            LEFT JOIN agg a ON a.department = g.department AND a.kpi_month = g.mm
        )
        SELECT
            CASE WHEN GROUPING(department) = 1 THEN '__ALL__' ELSE department END AS department,
            month,
            SUM(members)            AS members,
            SUM(check_all)          AS check_all,
            SUM(kpi_checked)        AS kpi_checked,
            SUM(checked)            AS checked,
            SUM(approved)           AS approved,
            SUM(wait_supervisor)    AS wait_supervisor,
            SUM(wait_manager)       AS wait_manager,
            SUM(auto_not_performed) AS auto_not_performed,
            SUM(off_cycle)          AS off_cycle,
            SUM(daily_use)          AS daily_use
        FROM joined
        GROUP BY GROUPING SETS ((department, month), (month))
        ORDER BY GROUPING(department) DESC, department, month
        """;

    public Mono<List<ChecklistStatsDTO>> getChecklistStats(Integer year, String department) {
        return ReactiveSecurityContextHolder.getContext()
                .mapNotNull(ctx -> (MemberPrincipal) Objects.requireNonNull(ctx.getAuthentication()).getPrincipal())
                .flatMap(principal -> {
                    int y = (year != null) ? year : LocalDate.now(ZONE).getYear();

                    // รอบ KPI ม.ค. อาจเริ่มปลาย ธ.ค. ปีก่อน → เผื่อช่วง แล้วกรองด้วยปีของวันศุกร์
                    Instant fromTs = LocalDate.of(y - 1, 12, 20).atStartOfDay(ZONE).toInstant();
                    Instant toTs   = LocalDate.of(y + 1, 1, 10).atStartOfDay(ZONE).toInstant();

                    var spec = template.getDatabaseClient().sql(STATS_SQL)
                            .bind("year",           String.valueOf(y))
                            .bind("role",           principal.role() != null ? principal.role() : "")
                            .bind("activeStatuses", activeStatuses())
                            .bind("fromTs",         fromTs)
                            .bind("toTs",           toTs);

                    spec = principal.memberId() != null
                            ? spec.bind("memberId", principal.memberId())
                            : spec.bindNull("memberId", Long.class);
                    spec = principal.departmentId() != null
                            ? spec.bind("deptId", principal.departmentId())
                            : spec.bindNull("deptId", Long.class);
                    spec = StringUtils.hasText(department)
                            ? spec.bind("deptName", department)
                            : spec.bindNull("deptName", String.class);

                    return spec.map((row, meta) -> mapRowToStatsDTO(row, y)).all().collectList();
                });
    }

    private static String[] activeStatuses() {
        // ถ้า activeDbValues() คืนค่าเป็น String[] อยู่แล้ว ให้ return ตรงๆ
        return MachineStatus.activeDbValues().stream()
                .map(String::valueOf)
                .toArray(String[]::new);
    }

    // =========================================================================
    //  VALIDATE
    // =========================================================================

    public Mono<ChecklistDTO> validateData(ChecklistDTO dto) {
        if (dto.getMachineId() == null && !StringUtils.hasText(dto.getMachineCode())) {
            return Mono.error(new ThrowException("MS_MACHINE_REQUIRED"));
        }

        if (!StringUtils.hasText(dto.getMachineStatus())) {
            return Mono.error(new ThrowException("MS008"));
        }

        String mc = dto.getMachineChecklist();
        if (!StringUtils.hasText(mc) || "[]".equals(mc.trim())) {
            log.warn("[CHECKLIST] validateData — machineChecklist empty for machineId={}", dto.getMachineId());
            return Mono.error(new ThrowException("MS_CHECKLIST_EMPTY"));
        }

        List<Long> ids = parseChecklistIds(mc);
        if (ids.isEmpty()) {
            log.warn("[CHECKLIST] validateData — machineChecklist has no valid ids for machineId={}", dto.getMachineId());
            return Mono.error(new ThrowException("MS_CHECKLIST_EMPTY"));
        }

        return Mono.just(dto);
    }

    public Mono<ChecklistDTO> validateDataUpdate(ChecklistDTO dto) {
        if (!StringUtils.hasText(dto.getMachineStatus())) {
            return Mono.error(new ThrowException("MS008"));
        }

        return ReactiveSecurityContextHolder.getContext()
                .mapNotNull(ctx -> (MemberPrincipal) Objects.requireNonNull(ctx.getAuthentication()).getPrincipal())
                .flatMap(principal -> {
                    Long    memberId = principal.memberId();
                    String  status   = dto.getChecklistStatus();
                    Instant now      = Instant.now();

                    log.info("[CHECKLIST] validateDataUpdate memberId={} status={} supervisor={} manager={}",
                            memberId, status, dto.getSupervisor(), dto.getManager());

                    if ("PENDING SUPERVISOR".equals(status)) {
                        if (!memberId.equals(dto.getSupervisor())) {
                            return Mono.error(new ThrowException("MS010"));
                        }
                        dto.setChecklistStatus(dto.getManager() != null ? "PENDING MANAGER" : "COMPLETED");
                        dto.setDateSupervisorChecked(now);

                    } else if ("PENDING MANAGER".equals(status)) {
                        if (!memberId.equals(dto.getManager())) {
                            return Mono.error(new ThrowException("MS010"));
                        }
                        dto.setChecklistStatus("COMPLETED");
                        dto.setDateManagerChecked(now);

                    } else {
                        return Mono.error(new ThrowException("MS011"));
                    }

                    return Mono.just(dto);
                });
    }

    // =========================================================================
    //  BUILD
    // =========================================================================

    public ChecklistRecord buildFromDTO(ChecklistDTO dto) {
        return ChecklistRecord.builder()
                .checkType(dto.getCheckType())
                .recheck(dto.getRecheck())
                .machineName(dto.getMachineName())
                .machineCode(dto.getMachineCode())
                .machineStatus(dto.getMachineStatus())
                .machineChecklist(dto.getMachineChecklist())
                .machineNote(dto.getMachineNote())
                .image(dto.getImage())
                .userId(dto.getUserId())
                .userName(dto.getUserName())
                .supervisor(dto.getSupervisor())
                .dateSupervisorChecked(dto.getDateSupervisorChecked())
                .manager(dto.getManager())
                .dateManagerChecked(dto.getDateManagerChecked())
                .checklistStatus(dto.getChecklistStatus())
                .reasonNotChecked(dto.getReasonNotChecked())
                .jobDetail(dto.getJobDetail())
                .build();
    }

    private Update buildUpdateFromDTO(ChecklistDTO dto) {
        Map<SqlIdentifier, Object> params = new HashMap<>();
        addIfNotNull(params, "checklist_status",        dto.getChecklistStatus());
        addIfNotNull(params, "date_supervisor_checked", dto.getDateSupervisorChecked());
        addIfNotNull(params, "date_manager_checked",    dto.getDateManagerChecked());
        addIfNotNull(params, "reason_not_checked",      dto.getReasonNotChecked());
        return Update.from(params);
    }

    // =========================================================================
    //  HELPERS
    // =========================================================================

    private List<Long> parseChecklistIds(String machineChecklist) {
        try {
            var node = objectMapper.readTree(machineChecklist);
            List<Long> ids = new ArrayList<>();
            if (node.isArray()) {
                for (var item : node) {
                    if (item.has("id") && !item.get("id").isNull()) {
                        ids.add(item.get("id").asLong());
                    }
                }
            }
            return ids;
        } catch (Exception e) {
            log.error("[CHECKLIST] Failed to parse machineChecklist: {}", e.getMessage());
            return List.of();
        }
    }

    private Criteria buildKeywordCriteria(String keyword) {
        if (StringUtils.hasText(keyword)) {
            return Criteria.where("machine_name").like("%" + keyword + "%").ignoreCase(true)
                    .or("machine_code").like("%" + keyword + "%").ignoreCase(true);
        }
        return Criteria.empty();
    }

    private Flux<ChecklistListDTO> convertChecklistListDTOs(List<ChecklistRecord> records) {
        return Flux.fromIterable(records).map(ChecklistListDTO::from);
    }

    private void addIfNotNull(Map<SqlIdentifier, Object> params, String fieldName, Object value) {
        if (value != null) params.put(SqlIdentifier.quoted(fieldName), value);
    }

    private Mono<Void> postDeleteTask(Long memberId, Long departmentId) {
        return Mono.empty();
    }

    // =========================================================================
    //  STATS MAPPING — ใช้ ChecklistStatsDTO เดิม (ไม่แก้ DTO)
    //
    //  ความหมายของ field:
    //    weeklyCheckDone            = อนุมัติแล้ว (COMPLETED)
    //    weeklyCheckWaitLeader      = รอหัวหน้า
    //    weeklyCheckWaitManager     = รอผู้จัดการ
    //    weeklyCheckPercent         = ตรวจแล้ว ÷ ต้องตรวจ   (= ตาราง KPI)
    //    weeklyApprovePercent       = อนุมัติแล้ว ÷ ตรวจแล้ว
    //    notCheckDone               = ขาดตรวจ (ต้องตรวจ − ตรวจแล้ว)
    //    notCheckDoneNotCheck       = ระบบบันทึกว่าไม่ได้ทำ (Automatic recording)
    //    notCheckApprovePercent     = % ขาดตรวจ (ขาดตรวจ ÷ ต้องตรวจ)
    //    notCheckWaitLeader/Manager = 0, notCheckApprovePercentFinal = null (ไม่ใช้แล้ว)
    //
    //  frontend คำนวณต่อได้:
    //    ตรวจแล้ว    = weeklyCheckDone + weeklyCheckWaitLeader + weeklyCheckWaitManager
    //    ต้องตรวจ    = ตรวจแล้ว + notCheckDone
    //    ตรวจนอกรอบ = dailyUse − ตรวจแล้ว − notCheckDoneNotCheck
    //    ไม่มี KPI เดือนนั้น ⇔ weeklyCheckPercent == null
    // =========================================================================

    private ChecklistStatsDTO mapRowToStatsDTO(io.r2dbc.spi.Row row, int year) {
        long checkAll    = statsLong(row, "check_all");
        long approved    = statsLong(row, "approved");
        long waitLeader  = statsLong(row, "wait_supervisor");
        long waitManager = statsLong(row, "wait_manager");
        long checked     = approved + waitLeader + waitManager;
        long missed      = Math.max(checkAll - checked, 0);

        return ChecklistStatsDTO.builder()
                .department(row.get("department", String.class))
                .month(row.get("month", Integer.class))
                .year(year)
                .dailyUse(statsLong(row, "daily_use"))
                .weeklyCheckDone(approved)
                .weeklyCheckWaitLeader(waitLeader)
                .weeklyCheckWaitManager(waitManager)
                .weeklyCheckPercent(statsPct(checked, checkAll))
                .weeklyApprovePercent(statsPct(approved, checked))
                .notCheckDone(missed)
                .notCheckDoneNotCheck(statsLong(row, "auto_not_performed"))
                .notCheckWaitLeader(0L)
                .notCheckWaitManager(0L)
                .notCheckApprovePercent(statsPct(missed, checkAll))
                .notCheckApprovePercentFinal(null)
                .build();
    }

    private static Integer statsPct(long num, long den) {
        return den > 0 ? (int) Math.round(num * 100.0 / den) : null;
    }

    private static long statsLong(io.r2dbc.spi.Row row, String col) {
        Object v = row.get(col);
        return v instanceof Number n ? n.longValue() : 0L;
    }
}