package com.acme.checklist.service;

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
import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChecklistService {
    private final R2dbcEntityTemplate template;
    private final CommonService commonService;
    private final ObjectMapper objectMapper;
    private final FileStorageService fileStorageService;
    private final KpiService kpiService;

    // ─── CREATE ───────────────────────────────────────────────────────────────

    public Mono<ApiResponse<Void>> create(String requestJson, FilePart file) {
        ChecklistDTO dto;
        try {
            dto = objectMapper.readValue(requestJson, ChecklistDTO.class);
        } catch (Exception e) {
            log.error("Parse error: {}", e.getMessage());
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
            log.error("Failed to create the checklist: {}", e.getMessage(), e);
            return Mono.just(ApiResponse.error("MS002", e.getMessage()));
        });
    }

    private Mono<ApiResponse<Void>> processSave(ChecklistDTO dto) {
        dto.setCheckType("GENERAL");
        return template.selectOne(
                        Query.query(Criteria.where("machine_code").is(dto.getMachineCode())),
                        Machine.class)
                .switchIfEmpty(Mono.error(new RuntimeException("Machine not found: " + dto.getMachineCode())))
                .flatMap(machine -> {
                    boolean isWeekend = LocalDate.now().getDayOfWeek() == DayOfWeek.SATURDAY
                            || LocalDate.now().getDayOfWeek() == DayOfWeek.SUNDAY;
                    boolean isResponsible = Objects.equals(dto.getUserId(), machine.getResponsiblePersonId());
                    boolean isPending = "PENDING".equals(machine.getCheckStatus());

                    ChecklistRecord record = buildFromDTO(dto);

                    if (isResponsible && isPending && !isWeekend) {
                        if (machine.getSupervisorId() != null) {
                            record.setChecklistStatus("PENDING SUPERVISOR");
                        } else {
                            record.setChecklistStatus("PENDING MANAGER");
                        }
                        record.setRecheck(true);

                        List<Long> checklistIds = parseChecklistIds(dto.getMachineChecklist());
                        Mono<Void> updateChecklistItems = Flux.fromIterable(checklistIds)
                                .flatMap(id -> template.update(MachineChecklist.class)
                                        .matching(Query.query(Criteria.where("id").is(id)))
                                        .apply(Update.update("check_status", true)))
                                .then();

                        Mono<Void> updateMachine = template.update(Machine.class)
                                .matching(Query.query(Criteria.where("machine_code").is(machine.getMachineCode())))
                                .apply(Update.update("check_status", record.getChecklistStatus())
                                        .set("machine_status", dto.getMachineStatus()))
                                .then();

                        return updateChecklistItems
                                .then(commonService.save(record, ChecklistRecord.class))
                                .flatMap(saved -> updateMachine
                                        .then(updateKpi(dto.getUserId()))
                                        .then(Mono.just(ApiResponse.<Void>success("MS001"))));
                    } else {
                        record.setChecklistStatus("COMPLETED");
                        record.setRecheck(false);

                        Mono<Void> updateMachine = template.update(Machine.class)
                                .matching(Query.query(Criteria.where("machine_code").is(machine.getMachineCode())))
                                .apply(Update.update("machine_status", dto.getMachineStatus()))
                                .then();

                        return commonService.save(record, ChecklistRecord.class)
                                .then(updateMachine)
                                .then(Mono.just(ApiResponse.<Void>success("MS001")));
                    }
                });
    }

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
            log.error("Failed to parse machineChecklist: {}", e.getMessage());
            return List.of();
        }
    }

    // ─── UPDATE ───────────────────────────────────────────────────────────────

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
                    log.error("Failed to update the checklist: {}", e.getMessage());
                    return Mono.just(ApiResponse.error("MS004", e.getMessage()));
                });
    }

    // ─── DELETE ───────────────────────────────────────────────────────────────

    public Mono<ApiResponse<Void>> delete(List<Long> ids) {
        return commonService.auditContext()
                .flatMap(ctx -> {
                    Long memberId     = ctx.get("X-Member-Id");
                    Long departmentId = ctx.get("X-Department-Id");
                    return commonService.deleteEntitiesByIds(
                            ids, ChecklistRecord.class,
                            "MS005", "MS006", "MS007",
                            ChecklistRecord::getMachineCode,
                            names -> postDeleteTask(names, memberId, departmentId));
                });
    }

    // ─── GET PAGE ─────────────────────────────────────────────────────────────

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
        String userIdStr = String.valueOf(userId);
        Criteria criteria = Criteria.where("created_by").is(userIdStr);
        if (StringUtils.hasText(keyword)) {
            criteria = criteria.and(
                    Criteria.where("machine_name").like("%" + keyword + "%").ignoreCase(true)
                            .or("machine_code").like("%" + keyword + "%").ignoreCase(true));
        }
        Query query = Query.query(criteria).with(commonService.pageable(index, size, "created_at"));
        return commonService.executePagedQuery(index, size, query, criteria, ChecklistRecord.class, this::convertChecklistListDTOs);
    }

    // ─── GET BY ID ────────────────────────────────────────────────────────────

    public Mono<ApiResponse<ChecklistResponseDTO>> getById(Long id) {
        return template.selectOne(Query.query(Criteria.where("id").is(id)), ChecklistRecord.class)
                .flatMap(checklistRecord -> {
                    List<Long> memberIds = new ArrayList<>();
                    if (checklistRecord.getCreatedBy() != null) memberIds.add(checklistRecord.getCreatedBy());
                    if (checklistRecord.getUpdatedBy() != null) memberIds.add(checklistRecord.getUpdatedBy());

                    Mono<Map<Long, Member>> membersMono = memberIds.isEmpty()
                            ? Mono.just(new HashMap<>())
                            : commonService.fetchMembersByIds(memberIds);

                    return membersMono.map(memberMap -> {
                        AuditMemberDTO createdByDTO = checklistRecord.getCreatedBy() != null
                                ? AuditMemberDTO.from(memberMap.get(checklistRecord.getCreatedBy())) : null;
                        AuditMemberDTO updatedByDTO = checklistRecord.getUpdatedBy() != null
                                ? AuditMemberDTO.from(memberMap.get(checklistRecord.getUpdatedBy())) : null;
                        return ApiResponse.success("MS017",
                                ChecklistResponseDTO.from(checklistRecord, createdByDTO, updatedByDTO));
                    });
                })
                .switchIfEmpty(Mono.just(ApiResponse.error("MS018", "Checklist not found")))
                .onErrorResume(e -> {
                    log.error("Failed to fetch checklist: {}", e.getMessage(), e);
                    return Mono.just(ApiResponse.error("MS019", e.getMessage()));
                });
    }

    // ─── GET WITH ROLE ────────────────────────────────────────────────────────

    public Mono<PagedResponse<ChecklistListDTO>> getWithRole(String keyword, int index, int size) {
        return ReactiveSecurityContextHolder.getContext()
                .map(ctx -> (MemberPrincipal) ctx.getAuthentication().getPrincipal())
                .flatMap(principal -> {
                    String role     = principal.role();
                    Long   memberId = principal.memberId();
                    String memberIdStr = String.valueOf(memberId);
                    log.info("getWithRole — role: {}, memberId: {}", role, memberId);

                    return switch (role) {
                        case "ADMIN" -> {
                            Criteria criteria = buildKeywordCriteria(keyword);
                            Query query = Query.query(criteria)
                                    .with(commonService.pageable(index, size, "created_at"));
                            yield commonService.executePagedQuery(
                                    index, size, query, criteria,
                                    ChecklistRecord.class, this::convertChecklistListDTOs);
                        }
                        default -> {
                            yield template.select(
                                            Query.query(Criteria.where("responsible_person_id").is(memberId)), // ← memberId
                                            Machine.class)
                                    .map(Machine::getMachineCode)
                                    .collectList()
                                    .flatMap(machineCodes -> {
                                        log.info("machineCodes: {}", machineCodes);

                                        Criteria criteria = Criteria
                                                .where("created_by").is(memberId)
                                                .or("machine_code").in(machineCodes)
                                                .or("supervisor").is(memberIdStr)
                                                .or("manager").is(memberIdStr);

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
                    };
                });
    }

    // ─── GET PENDING APPROVALS ────────────────────────────────────────────────

    public Mono<ListResponse<List<ChecklistListDTO>>> getPendingApprovals() {
        return ReactiveSecurityContextHolder.getContext()
                .map(ctx -> (MemberPrincipal) ctx.getAuthentication().getPrincipal())
                .flatMap(principal -> {
                    Long   memberId    = principal.memberId();
                    String role        = principal.role();
                    String memberIdStr = String.valueOf(memberId);

                    log.info("getPendingApprovals — role: {}, memberId: {}", role, memberId);

                    if (!role.equals("SUPERVISOR") && !role.equals("MANAGER")) {
                        return Mono.just(ListResponse.success("MS022", false, List.<ChecklistListDTO>of()));
                    }

                    Criteria criteria = Criteria
                            .where("checklist_status").is("PENDING SUPERVISOR")
                            .and("supervisor").is(memberIdStr)
                            .or(Criteria.where("checklist_status").is("PENDING MANAGER")
                                    .and("manager").is(memberIdStr));

                    log.info("getPendingApprovals — memberIdStr: {}", memberIdStr);

                    Query query = Query.query(criteria)
                            .with(commonService.pageable(0, 100, "created_at"));

                    return template.select(query, ChecklistRecord.class)
                            .map(ChecklistListDTO::from)
                            .collectList()
                            .doOnNext(list -> log.info("getPendingApprovals — found: {} items", list.size()))
                            .map(list -> ListResponse.success("MS022", false, list));
                })
                .onErrorResume(e -> {
                    log.error("Failed to fetch pending approvals: {}", e.getMessage(), e);
                    return Mono.just(ListResponse.error("MS022"));
                });
    }

    // ─── STATS ────────────────────────────────────────────────────────────────

    public Mono<List<ChecklistStatsDTO>> getChecklistStats(Integer year, String department) {
        return ReactiveSecurityContextHolder.getContext()
                .map(ctx -> (MemberPrincipal) ctx.getAuthentication().getPrincipal())
                .flatMap(principal -> {
                    int    targetYear = (year != null) ? year : LocalDate.now().getYear();
                    String role       = principal.role();
                    Long   memberId   = principal.memberId();  // ← memberId

                    String roleFilter = switch (role) {
                        case "MEMBER"     -> "AND m.responsible_person_id = " + memberId;
                        case "SUPERVISOR" -> "AND (m.responsible_person_id = " + memberId + " OR m.supervisor_id = " + memberId + ")";
                        case "MANAGER"    -> "AND (m.responsible_person_id = " + memberId + " OR m.manager_id = " + memberId + ")";
                        default           -> "";
                    };

                    String deptFilter = StringUtils.hasText(department)
                            ? "AND d.department_code = :department" : "";

                    String sql = buildStatsSQL(roleFilter, deptFilter);

                    var spec = template.getDatabaseClient()
                            .sql(sql)
                            .bind("year", targetYear);

                    if (StringUtils.hasText(department)) {
                        spec = spec.bind("department", department);
                    }

                    return spec.map((row, metadata) -> mapRowToStatsDTO(row)).all().collectList();
                });
    }

    // ─── VALIDATE ─────────────────────────────────────────────────────────────

    public Mono<ChecklistDTO> validateData(ChecklistDTO checklistDTO) {
        if (checklistDTO.getMachineStatus() == null || checklistDTO.getMachineStatus().isEmpty()) {
            return Mono.error(new ThrowException("MS008"));
        }
        return Mono.just(checklistDTO);
    }

    public Mono<ChecklistDTO> validateDataUpdate(ChecklistDTO dto) {
        if (dto.getMachineStatus() == null || dto.getMachineStatus().isEmpty()) {
            return Mono.error(new ThrowException("MS008"));
        }

        return ReactiveSecurityContextHolder.getContext()
                .map(ctx -> (MemberPrincipal) ctx.getAuthentication().getPrincipal())
                .flatMap(principal -> {
                    Long   memberId    = principal.memberId();
                    String memberIdStr = String.valueOf(memberId);
                    String status      = dto.getChecklistStatus();
                    Instant now        = Instant.now();

                    log.info("validateDataUpdate — memberId: {}, status: {}, supervisor: {}, manager: {}",
                            memberIdStr, status, dto.getSupervisor(), dto.getManager());

                    if ("PENDING SUPERVISOR".equals(status)) {
                        if (!memberIdStr.equals(dto.getSupervisor())) {
                            return Mono.error(new ThrowException("MS010"));
                        }
                        dto.setChecklistStatus(dto.getManager() != null ? "PENDING MANAGER" : "COMPLETED");
                        dto.setDateSupervisorChecked(now);

                    } else if ("PENDING MANAGER".equals(status)) {
                        if (!memberIdStr.equals(dto.getManager())) {
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

    // ─── BUILD ────────────────────────────────────────────────────────────────

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
        return Update.from(params);
    }

    private String buildStatsSQL(String roleFilter, String deptFilter) {
        return """
            SELECT
                d.department,
                EXTRACT(MONTH FROM cr.created_at)::int AS month,
                EXTRACT(YEAR  FROM cr.created_at)::int AS year,
                COUNT(cr.id) AS daily_use,
                COUNT(CASE WHEN cr.recheck = true  AND cr.checklist_status = 'COMPLETED'          THEN 1 END) AS weekly_check_done,
                COUNT(CASE WHEN cr.recheck = true  AND cr.checklist_status = 'PENDING SUPERVISOR' THEN 1 END) AS weekly_check_wait_leader,
                COUNT(CASE WHEN cr.recheck = true  AND cr.checklist_status = 'PENDING MANAGER'   THEN 1 END) AS weekly_check_wait_manager,
                COUNT(CASE WHEN cr.recheck = false AND cr.checklist_status = 'COMPLETED' AND cr.reason_not_checked IS NULL     THEN 1 END) AS not_check_done,
                COUNT(CASE WHEN cr.recheck = false AND cr.checklist_status = 'COMPLETED' AND cr.reason_not_checked IS NOT NULL THEN 1 END) AS not_check_done_not_check,
                COUNT(CASE WHEN cr.recheck = false AND cr.checklist_status = 'PENDING SUPERVISOR' THEN 1 END) AS not_check_wait_leader,
                COUNT(CASE WHEN cr.recheck = false AND cr.checklist_status = 'PENDING MANAGER'   THEN 1 END) AS not_check_wait_manager,
                COUNT(CASE WHEN cr.recheck = false AND cr.checklist_status = 'COMPLETED'         THEN 1 END) AS not_check_final_done,
                COUNT(CASE WHEN cr.recheck = false THEN 1 END) AS not_check_total
            FROM checklist_record cr
            JOIN machine m ON cr.machine_code = m.machine_code
            JOIN department d ON m.department = d.department_code
            WHERE EXTRACT(YEAR FROM cr.created_at) = :year
              AND cr.check_type = 'GENERAL'
              %s
              %s
            GROUP BY d.department, EXTRACT(MONTH FROM cr.created_at), EXTRACT(YEAR FROM cr.created_at)
            ORDER BY d.department, month
        """.formatted(roleFilter, deptFilter);
    }

    // ─── KPI ──────────────────────────────────────────────────────────────────

    private Mono<Void> updateKpi(String userId) {
        LocalDate now = LocalDate.now();
        String year  = String.valueOf(now.getYear());
        String month = String.format("%02d", now.getMonthValue());
        log.info("Updating KPI for user: {}, year: {}, month: {}", userId, year, month);
        return kpiService.updateOrCreateKpi(userId, year, month);
    }

    // ─── HELPERS ──────────────────────────────────────────────────────────────

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

    private Mono<Void> postDeleteTask(List<String> names, Long memberId, Long departmentId) {
        return Mono.empty();
    }

    private Flux<ChecklistStatsDTO> executeStatsQuery(String sql, int year, String department) {
        var spec = template.getDatabaseClient().sql(sql).bind("year", year);
        if (StringUtils.hasText(department)) spec = spec.bind("department", department);
        return spec.map((row, metadata) -> mapRowToStatsDTO(row)).all();
    }

    private ChecklistStatsDTO mapRowToStatsDTO(io.r2dbc.spi.Row row) {
        Long done        = row.get("weekly_check_done",         Long.class);
        Long waitLeader  = row.get("weekly_check_wait_leader",  Long.class);
        Long waitManager = row.get("weekly_check_wait_manager", Long.class);
        Long ncDone      = row.get("not_check_done",            Long.class);
        Long ncDoneNc    = row.get("not_check_done_not_check",  Long.class);
        Long ncLeader    = row.get("not_check_wait_leader",     Long.class);
        Long ncManager   = row.get("not_check_wait_manager",    Long.class);
        Long ncFinalDone = row.get("not_check_final_done",      Long.class);
        Long ncTotal     = row.get("not_check_total",           Long.class);

        return ChecklistStatsDTO.builder()
                .department(row.get("department",  String.class))
                .month(row.get("month",  Integer.class))
                .year(row.get("year",    Integer.class))
                .dailyUse(row.get("daily_use", Long.class))
                .weeklyCheckDone(done)
                .weeklyCheckWaitLeader(waitLeader)
                .weeklyCheckWaitManager(waitManager)
                .weeklyCheckPercent(calculateWeeklyCheckPercent(done, waitLeader, waitManager))
                .weeklyApprovePercent(calculateWeeklyApprovePercent(done, waitManager))
                .notCheckDone(ncDone)
                .notCheckDoneNotCheck(ncDoneNc)
                .notCheckWaitLeader(ncLeader)
                .notCheckWaitManager(ncManager)
                .notCheckApprovePercent(calculateNotCheckApprovePercent(ncDone, ncDoneNc, ncLeader, ncManager))
                .notCheckApprovePercentFinal(calculateNotCheckApprovePercentFinal(ncFinalDone, ncTotal))
                .build();
    }

    private int calculateWeeklyCheckPercent(Long done, Long waitLeader, Long waitManager) {
        long total = safe(done) + safe(waitLeader) + safe(waitManager);
        return total > 0 ? (int) Math.round((safe(done) * 100.0) / total) : 0;
    }

    private int calculateWeeklyApprovePercent(Long done, Long waitManager) {
        long total = safe(done) + safe(waitManager);
        return total > 0 ? (int) Math.round((safe(done) * 100.0) / total) : 0;
    }

    private int calculateNotCheckApprovePercent(Long done, Long doneNotCheck, Long waitLeader, Long waitManager) {
        long total = safe(done) + safe(doneNotCheck) + safe(waitLeader) + safe(waitManager);
        return total > 0 ? (int) Math.round((safe(done) * 100.0) / total) : 0;
    }

    private int calculateNotCheckApprovePercentFinal(Long finalDone, Long total) {
        return safe(total) > 0 ? (int) Math.round((safe(finalDone) * 100.0) / safe(total)) : 0;
    }

    private long safe(Long val) {
        return val != null ? val : 0L;
    }
}