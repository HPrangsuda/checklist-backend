package com.acme.checklist.service;

import com.acme.checklist.entity.*;
import com.acme.checklist.exception.ThrowException;
import com.acme.checklist.payload.ApiResponse;
import com.acme.checklist.payload.ListResponse;
import com.acme.checklist.payload.MemberPrincipal;
import com.acme.checklist.payload.PagedResponse;
import com.acme.checklist.payload.audit.AuditMemberDTO;
import com.acme.checklist.payload.calibration.CalibrationDTO;
import com.acme.checklist.payload.calibration.CalibrationResponseDTO;
import com.acme.checklist.payload.machine.MachineDTO;
import com.acme.checklist.payload.machine.MachineListDTO;
import com.acme.checklist.payload.machine.MachineResponseDTO;
import com.acme.checklist.payload.machine.MachineSummaryDTO;
import com.acme.checklist.payload.maintenance.MaintenanceDTO;
import com.acme.checklist.payload.maintenance.MaintenanceResponseDTO;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
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
import reactor.core.scheduler.Schedulers;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class MachineService {

    private final R2dbcEntityTemplate template;
    private final CommonService commonService;

    private static final List<String> ACTIVE_STATUSES = List.of("IN USE", "NOT IN USE", "UNDER MAINTENANCE");

    // ─── CREATE ───────────────────────────────────────────────────────────────

    public Mono<ApiResponse<Void>> create(MachineDTO dto) {
        dto.setId(null);
        return validateData(dto, false)
                .flatMap(validateDTO -> resolveDepartmentFields(validateDTO)
                        .flatMap(resolvedDTO -> {
                            Machine machine = buildFromDTO(resolvedDTO);
                            return commonService.save(machine, Machine.class)
                                    .flatMap(savedMachine -> createRelatedRecords(savedMachine, resolvedDTO))
                                    .then(Mono.just(ApiResponse.success("MS001")));
                        }))
                .onErrorResume(e -> {
                    log.error("Failed to create the machine", e);
                    String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                    if (e instanceof NullPointerException) msg = "ข้อมูลบางส่วนเป็น null กรุณาตรวจสอบข้อมูล";
                    return Mono.just(ApiResponse.error("MS002", msg));
                });
    }

    private Mono<Void> createRelatedRecords(Machine machine, MachineDTO dto) {
        List<Mono<Void>> tasks = new ArrayList<>();
        if (dto.getCalibration() != null && dto.getCalibration().getDueDate() != null)
            tasks.add(createCalibrationRecord(machine, dto.getCalibration()));
        if (dto.getMaintenanceList() != null && !dto.getMaintenanceList().isEmpty())
            tasks.add(createMaintenanceRecords(machine, dto.getMaintenanceList()).then());

        if (machine.getResponsiblePersonId() != null) {
            ResponsibleHistory history = ResponsibleHistory.builder()
                    .machineCode(machine.getMachineCode())
                    .responsiblePersonId(machine.getResponsiblePersonId())
                    .effectiveFrom(LocalDate.now())
                    .effectiveTo(null)
                    .build();
            tasks.add(template.insert(history).then());
        }

        return Mono.when(tasks);
    }

    private Mono<Void> createCalibrationRecord(Machine machine, CalibrationDTO dto) {
        CalibrationRecord cal = new CalibrationRecord();
        cal.setMachineCode(machine.getMachineCode());
        cal.setMachineName(machine.getMachineName());
        cal.setYears(dto.getDueDate().getYear());
        cal.setDueDate(dto.getDueDate());
        cal.setCertificateDate(dto.getCertificateDate());
        cal.setResults(dto.getResults());
        cal.setCriteria(dto.getCriteria());
        cal.setMeasuringRange(dto.getMeasuringRange());
        cal.setAccuracy(dto.getAccuracy());
        cal.setCalibrationRange(dto.getCalibrationRange());
        cal.setCalibrationStatus(dto.getCalibrationStatus());
        cal.setAttachment(dto.getAttachment());
        return commonService.save(cal, CalibrationRecord.class).then();
    }

    private Mono<Void> createMaintenanceRecords(Machine machine, List<MaintenanceDTO> list) {
        if (list == null || list.isEmpty()) return Mono.empty();
        return Flux.fromIterable(list)
                .flatMap(dto -> {
                    MaintenanceRecord r = new MaintenanceRecord();
                    r.setMachineCode(machine.getMachineCode());
                    r.setMachineName(machine.getMachineName());
                    r.setRound(dto.getRound());
                    r.setYears(dto.getYears());
                    r.setDueDate(dto.getDueDate());
                    r.setPlanDate(dto.getPlanDate());
                    r.setActualDate(dto.getActualDate());
                    r.setStatus(dto.getStatus() != null ? dto.getStatus() : "On Time");
                    r.setMaintenanceBy(dto.getMaintenanceBy());
                    r.setNote(dto.getNote());
                    r.setAttachment(dto.getAttachment());
                    return commonService.save(r, MaintenanceRecord.class);
                }).then();
    }

    // ─── UPDATE ───────────────────────────────────────────────────────────────

    public Mono<ApiResponse<Void>> update(MachineDTO machineDTO) {
        return validateData(machineDTO, true)
                .flatMap(v -> template.selectOne(
                                Query.query(Criteria.where("id").is(v.getId())),
                                Machine.class)
                        .flatMap(existing -> {
                            Long oldPersonId = existing.getResponsiblePersonId();
                            Long newPersonId = v.getResponsiblePersonId() != null
                                    ? Long.valueOf(v.getResponsiblePersonId()) : null;
                            boolean personChanged = newPersonId != null && !newPersonId.equals(oldPersonId);

                            Mono<Void> updateMachine = commonService
                                    .update(machineDTO.getId(), buildUpdateFromDTO(v), Machine.class).then();

                            if (!personChanged) {
                                return updateMachine.then(Mono.just(ApiResponse.<Void>success("MS003")));
                            }

                            LocalDate today     = LocalDate.now();
                            LocalDate yesterday = today.minusDays(1);

                            Mono<Void> closeOld = template.update(
                                    Query.query(Criteria.where("machine_code").is(existing.getMachineCode())
                                            .and("effective_to").isNull()),
                                    Update.update("effective_to", yesterday),
                                    ResponsibleHistory.class).then();

                            ResponsibleHistory newHistory = ResponsibleHistory.builder()
                                    .machineCode(existing.getMachineCode())
                                    .responsiblePersonId(newPersonId)
                                    .effectiveFrom(today)
                                    .effectiveTo(null)
                                    .build();
                            Mono<Void> insertNew = template.insert(newHistory).then();

                            return updateMachine
                                    .then(closeOld)
                                    .then(insertNew)
                                    .then(recalculateKpiForPerson(oldPersonId))
                                    .then(recalculateKpiForPerson(newPersonId))
                                    .then(Mono.just(ApiResponse.<Void>success("MS003")));
                        }))
                .onErrorResume(e -> {
                    log.error("Failed to update the machine: {}", e.getMessage());
                    return Mono.just(ApiResponse.error("MS004", e.getMessage()));
                });
    }

    // ─── DELETE ───────────────────────────────────────────────────────────────

    public Mono<ApiResponse<Void>> delete(List<Long> ids) {
        return commonService.auditContext()
                .flatMap(ctx -> commonService.deleteEntitiesByIds(
                        ids, Machine.class,
                        "MS005", "MS006", "MS007",
                        Machine::getMachineName,
                        names -> postDeleteTask(names, ctx.get("X-Member-Id"), ctx.get("X-Department-Id"))));
    }

    // ─── CHANGE RESPONSIBLE PERSON ────────────────────────────────────────────

    public Mono<ApiResponse<Void>> changeResponsiblePerson(String machineCode, Long newPersonId) {
        LocalDate today     = LocalDate.now();
        LocalDate yesterday = today.minusDays(1);

        return template.selectOne(
                        Query.query(Criteria.where("machine_code").is(machineCode)),
                        Machine.class)
                .switchIfEmpty(Mono.error(new ThrowException("MS030", "Machine not found: " + machineCode)))
                .flatMap(machine -> {
                    Long oldPersonId = machine.getResponsiblePersonId();

                    Mono<Void> closeOld = template.update(
                                    Query.query(Criteria.where("machine_code").is(machineCode)
                                            .and("effective_to").isNull()),
                                    Update.update("effective_to", yesterday),
                                    ResponsibleHistory.class)
                            .then();

                    ResponsibleHistory newHistory = ResponsibleHistory.builder()
                            .machineCode(machineCode)
                            .responsiblePersonId(newPersonId)
                            .effectiveFrom(today)
                            .build();
                    Mono<Void> insertNew = template.insert(newHistory).then();

                    machine.setResponsiblePersonId(newPersonId);
                    Mono<Void> updateMachine = template.update(machine).then();

                    return closeOld
                            .then(insertNew)
                            .then(updateMachine)
                            .then(recalculateKpiForPerson(oldPersonId))
                            .then(recalculateKpiForPerson(newPersonId))
                            .doOnSuccess(v -> log.info("Changed responsible {} → {} for machine {}",
                                    oldPersonId, newPersonId, machineCode))
                            .then(Mono.just(ApiResponse.<Void>success("MS031")));
                })
                .onErrorResume(e -> {
                    log.error("Failed to change responsible person: {}", e.getMessage());
                    return Mono.just(ApiResponse.error("MS032", e.getMessage()));
                });
    }

    private Mono<Void> recalculateKpiForPerson(Long memberId) {
        if (memberId == null) return Mono.empty();

        LocalDate today      = LocalDate.now();
        YearMonth ym         = YearMonth.from(today);
        String year          = String.valueOf(today.getYear());
        String month         = String.format("%02d", today.getMonthValue());
        LocalDate firstDay   = ym.atDay(1);
        LocalDate lastDay    = ym.atEndOfMonth();
        LocalDate lastFriday = getLastFridayOfMonth(ym);

        Criteria historyCriteria = Criteria
                .where("responsible_person_id").is(memberId)
                .and("effective_from").lessThanOrEquals(lastDay)
                .and(Criteria.where("effective_to").isNull()
                        .or(Criteria.where("effective_to").greaterThanOrEquals(firstDay)));

        Mono<Long> newCheckAllMono = template.select(
                        Query.query(historyCriteria), ResponsibleHistory.class)
                .filterWhen(h -> isMachineActive(h.getMachineCode()))
                .collectList()
                .map(histories -> histories.stream()
                        .mapToLong(h -> countFridaysInRange(
                                clampStart(h.getEffectiveFrom(), firstDay),
                                clampEnd(h.getEffectiveTo(), lastFriday)))
                        .sum());

        Mono<Member> memberMono = template.selectOne(
                Query.query(Criteria.where("id").is(memberId)),
                Member.class);

        return Mono.zip(newCheckAllMono, memberMono)
                .flatMap(tuple -> {
                    long newCheckAll = tuple.getT1();
                    Member member    = tuple.getT2();

                    return template.selectOne(
                                    Query.query(Criteria.where("member_id").is(memberId)
                                            .and("years").is(year)
                                            .and("months").is(month)),
                                    Kpi.class)
                            .flatMap(kpi -> {
                                if (newCheckAll == 0) return Mono.empty();
                                kpi.setCheckAll(newCheckAll);
                                kpi.setManagerId(member.getManager());
                                kpi.setSupervisorId(member.getSupervisor());
                                return template.update(kpi).then();
                            })
                            .switchIfEmpty(Mono.defer(() -> {
                                if (newCheckAll == 0) return Mono.empty();
                                Kpi newKpi = Kpi.builder()
                                        .memberId(memberId)
                                        .employeeName(member.getFirstName() + " " + member.getLastName())
                                        .years(year)
                                        .months(month)
                                        .checkAll(newCheckAll)
                                        .checked(0L)
                                        .managerId(member.getManager())
                                        .supervisorId(member.getSupervisor())
                                        .build();
                                return template.insert(newKpi).then();
                            }));
                })
                .doOnSuccess(v -> log.info("Recalculated KPI for memberId={}", memberId))
                .onErrorResume(e -> {
                    log.error("Failed to recalculate KPI for memberId={}: {}", memberId, e.getMessage());
                    return Mono.empty();
                });
    }

    // ─── GET WITH ROLE ────────────────────────────────────────────────────────

    public Mono<PagedResponse<MachineListDTO>> getByRole(String keyword, int index, int size) {
        return ReactiveSecurityContextHolder.getContext()
                .map(ctx -> (MemberPrincipal) ctx.getAuthentication().getPrincipal())
                .flatMap(principal -> {
                    String role     = principal.role();
                    Long   memberId = principal.memberId();

                    return switch (role) {
                        case "ADMIN" -> {
                            Criteria criteria = buildKeywordCriteria(keyword);
                            Query query = Query.query(criteria).with(commonService.pageable(index, size, "created_at"));
                            yield commonService.executePagedQuery(index, size, query, criteria, Machine.class, this::convertMachineListDTOs);
                        }
                        case "MANAGER" -> fetchWithRoleAndKeyword(
                                Criteria.where("responsible_person_id").is(memberId)
                                        .or("manager_id").is(memberId),
                                keyword, index, size);
                        case "SUPERVISOR" -> fetchWithRoleAndKeyword(
                                Criteria.where("responsible_person_id").is(memberId)
                                        .or("supervisor_id").is(memberId),
                                keyword, index, size);
                        default -> fetchWithRoleAndKeyword(
                                Criteria.where("responsible_person_id").is(memberId),
                                keyword, index, size);
                    };
                });
    }

    private Mono<PagedResponse<MachineListDTO>> fetchWithRoleAndKeyword(
            Criteria roleCriteria, String keyword, int index, int size) {
        Criteria criteria = roleCriteria;
        if (StringUtils.hasText(keyword)) {
            criteria = roleCriteria.and(
                    Criteria.where("machine_name").like("%" + keyword + "%").ignoreCase(true)
                            .or("machine_code").like("%" + keyword + "%").ignoreCase(true));
        }
        Query query = Query.query(criteria).with(commonService.pageable(index, size, "created_at"));
        return commonService.executePagedQuery(index, size, query, criteria, Machine.class, this::convertMachineListDTOs);
    }

    // ─── DEPARTMENT SUMMARY WITH ROLE ─────────────────────────────────────────

    public Flux<MachineSummaryDTO> getDepartmentSummaryWithRole() {
        return ReactiveSecurityContextHolder.getContext()
                .map(ctx -> (MemberPrincipal) ctx.getAuthentication().getPrincipal())
                .flatMapMany(principal -> {
                    String role     = principal.role();
                    Long   memberId = principal.memberId();

                    String roleFilter = switch (role) {
                        case "ADMIN"      -> "";
                        case "MANAGER"    -> "AND (m.responsible_person_id = " + memberId + " OR m.manager_id = " + memberId + ")";
                        case "SUPERVISOR" -> "AND (m.responsible_person_id = " + memberId + " OR m.supervisor_id = " + memberId + ")";
                        default           -> "AND m.responsible_person_id = " + memberId;
                    };

                    String sql = """
                    SELECT
                        d.department_code,
                        d.department as department_name,
                        COUNT(m.id) as total,
                        COUNT(CASE WHEN UPPER(m.machine_status) = 'READY TO USE' THEN 1 END) as total_ready_to_use,
                        COUNT(CASE WHEN UPPER(m.machine_status) = 'REPAIR'       THEN 1 END) as total_repair,
                        COUNT(CASE WHEN UPPER(m.machine_status) = 'NOT IN USE'   THEN 1 END) as total_not_in_use,
                        COUNT(CASE WHEN UPPER(m.check_status) = 'COMPLETED'      THEN 1 END) as total_completed,
                        COUNT(CASE WHEN UPPER(m.check_status) LIKE '%%PENDING%%' THEN 1 END) as total_pending,
                        COUNT(CASE WHEN UPPER(m.check_status) = 'APPROVE'        THEN 1 END) as total_approve
                    FROM machine m
                    JOIN department d ON m.department = d.department_code
                    WHERE 1=1 %s
                    GROUP BY d.department_code, d.department
                    ORDER BY d.department
                    """.formatted(roleFilter);

                    return template.getDatabaseClient()
                            .sql(sql)
                            .map((row, metadata) -> {
                                long total      = getLongValue(row, "total");
                                long readyToUse = getLongValue(row, "total_ready_to_use");
                                long repair     = getLongValue(row, "total_repair");
                                long notInUse   = getLongValue(row, "total_not_in_use");
                                long completed  = getLongValue(row, "total_completed");
                                long pending    = getLongValue(row, "total_pending");
                                long approve    = getLongValue(row, "total_approve");
                                return MachineSummaryDTO.builder()
                                        .department(row.get("department_code", String.class))
                                        .departmentName(row.get("department_name", String.class))
                                        .total(total)
                                        .totalReadyToUse(readyToUse)
                                        .totalRepair(repair)
                                        .totalNotInUse(notInUse)
                                        .totalCompleted(completed)
                                        .totalPending(pending)
                                        .totalApprove(approve)
                                        .readyRate(total > 0 ? (readyToUse * 100.0) / total : 0)
                                        .completedRate(total > 0 ? (completed * 100.0) / total : 0)
                                        .approveRate(total > 0 ? (approve * 100.0) / total : 0)
                                        .build();
                            })
                            .all()
                            .onErrorResume(e -> {
                                log.error("Error fetching machine department summary with role", e);
                                return Flux.empty();
                            });
                });
    }

    // ─── GET LIST ─────────────────────────────────────────────────────────────

    public Mono<ListResponse<List<MachineListDTO>>> getList(String keyword, List<Long> ids, int index, int size) {
        Pageable pageable = PageRequest.of(index, size, Sort.by(Sort.Direction.DESC, "id"));
        boolean hasIds = ids != null && !ids.isEmpty();
        return commonService.getSelectedItems(hasIds, ids, index, size, Machine.class)
                .flatMap(selectedItems -> {
                    Criteria criteria = Criteria.empty();
                    if (StringUtils.hasText(keyword) && hasIds)
                        criteria = Criteria.where("machine_name").like("%" + keyword + "%").ignoreCase(true).and("id").notIn(ids);
                    else if (StringUtils.hasText(keyword))
                        criteria = Criteria.where("name").like("%" + keyword + "%").ignoreCase(true);
                    else if (hasIds)
                        criteria = Criteria.where("id").notIn(ids);
                    return commonService.getPagedList(index, size, criteria, selectedItems, pageable, Machine.class, this::convertMachineListDTOs);
                });
    }

    // ─── GET BY ID ────────────────────────────────────────────────────────────

    public Mono<ApiResponse<MachineResponseDTO>> getById(Long id) {
        return template.selectOne(Query.query(Criteria.where("id").is(id)), Machine.class)
                .flatMap(machine -> {
                    String machineCode  = machine.getMachineCode();
                    String machineGroup = machine.getMachineGroupId();
                    String machineType  = machine.getMachineTypeId();
                    String deptCode     = machine.getDepartment();

                    if (machine.getQrCode() == null || machine.getQrCode().isEmpty())
                        return Mono.just(ApiResponse.<MachineResponseDTO>error("MS020", "QR code data is missing"));

                    Mono<List<CalibrationRecord>> calibMono = template
                            .select(Query.query(Criteria.where("machine_code").is(machineCode)), CalibrationRecord.class)
                            .collectList();
                    Mono<List<MaintenanceRecord>> maintMono = template
                            .select(Query.query(Criteria.where("machine_code").is(machineCode)), MaintenanceRecord.class)
                            .collectList();

                    List<Long> auditIds = new ArrayList<>();
                    if (machine.getCreatedBy() != null) auditIds.add(machine.getCreatedBy());
                    if (machine.getUpdatedBy() != null) auditIds.add(machine.getUpdatedBy());
                    Mono<Map<Long, Member>> auditMono = auditIds.isEmpty()
                            ? Mono.just(new HashMap<>()) : commonService.fetchMembersByIds(auditIds);

                    Mono<String> qrMono      = generateQRCodeReactive(machine.getQrCode(), machineCode);
                    Mono<String> supNameMono = resolveMemberName(machine.getSupervisorId());
                    Mono<String> mgrNameMono = resolveMemberName(machine.getManagerId());

                    Mono<String> groupNameMono = Mono.justOrEmpty(machineGroup)
                            .flatMap(gid -> template.getDatabaseClient()
                                    .sql("SELECT machine_group_name FROM machine_type WHERE machine_group_id = $1 LIMIT 1")
                                    .bind("$1", gid)
                                    .map((row, meta) -> row.get("machine_group_name", String.class))
                                    .first())
                            .defaultIfEmpty(machineGroup != null ? machineGroup : "");

                    Mono<String> typeNameMono = Mono.justOrEmpty(machineType)
                            .flatMap(tid -> template.getDatabaseClient()
                                    .sql("SELECT machine_type_name FROM machine_type WHERE machine_group_id = $1 AND machine_type_id = $2 LIMIT 1")
                                    .bind("$1", machineGroup)
                                    .bind("$2", tid)
                                    .map((row, meta) -> row.get("machine_type_name", String.class))
                                    .first())
                            .defaultIfEmpty(machineType != null ? machineType : "");

                    Mono<String> deptNameMono = Mono.justOrEmpty(deptCode)
                            .flatMap(code -> template.selectOne(
                                            Query.query(Criteria.where("department_code").is(code)),
                                            Department.class)
                                    .map(Department::getDepartment))
                            .defaultIfEmpty(deptCode != null ? deptCode : "");

                    return Mono.zip(calibMono, maintMono, auditMono, qrMono, supNameMono, mgrNameMono)
                            .flatMap(t6 -> Mono.zip(groupNameMono, typeNameMono, deptNameMono)
                                    .map(t3 -> {
                                        MachineResponseDTO dto = MachineResponseDTO.from(
                                                machine,
                                                machine.getCreatedBy() != null ? AuditMemberDTO.from(t6.getT3().get(machine.getCreatedBy())) : null,
                                                machine.getUpdatedBy() != null ? AuditMemberDTO.from(t6.getT3().get(machine.getUpdatedBy())) : null);
                                        dto.setCalibrationRecords(t6.getT1().stream().map(CalibrationResponseDTO::from).toList());
                                        dto.setMaintenanceRecords(t6.getT2().stream().map(MaintenanceResponseDTO::from).toList());
                                        dto.setQrCode(t6.getT4());
                                        dto.setSupervisorName(t6.getT5());
                                        dto.setManagerName(t6.getT6());
                                        dto.setMachineGroupName(t3.getT1());
                                        dto.setMachineTypeName(t3.getT2());
                                        dto.setDepartmentName(t3.getT3());
                                        return ApiResponse.success("MS017", dto);
                                    }));
                })
                .onErrorResume(e -> {
                    log.error("Failed to fetch machine {}: {}", id, e.getMessage(), e);
                    return Mono.just(ApiResponse.error("MS019", e.getMessage() != null ? e.getMessage() : "Unknown error"));
                });
    }

    // ─── GET BY MACHINE CODE ──────────────────────────────────────────────────

    public Mono<ApiResponse<Machine>> getByMachineCode(String machineCode) {
        return template.selectOne(Query.query(Criteria.where("machine_code").is(machineCode)), Machine.class)
                .map(m -> ApiResponse.success("MS017", m))
                .switchIfEmpty(Mono.just(ApiResponse.error("MS018", "Machine not found with machineCode: " + machineCode)))
                .onErrorResume(e -> Mono.just(ApiResponse.error("MS019", e.getMessage() != null ? e.getMessage() : "Unknown error")));
    }

    // ─── VALIDATE ─────────────────────────────────────────────────────────────

    public Mono<MachineDTO> validateData(MachineDTO dto, boolean isUpdate) {
        if (dto.getDepartment() == null || dto.getDepartment().isEmpty())
            return Mono.error(new ThrowException("MS008", "Department is required"));
        if (dto.getMachineCode() == null || dto.getMachineCode().isEmpty())
            return Mono.error(new ThrowException("MS009", "Machine code is required"));
        if (dto.getMachineName() == null || dto.getMachineName().isEmpty())
            return Mono.error(new ThrowException("MS010", "Machine name is required"));
        if (dto.getMachineStatus() == null || dto.getMachineStatus().isEmpty())
            return Mono.error(new ThrowException("MS011", "Machine status is required"));
        if (dto.getMachineTypeId() == null || dto.getMachineTypeId().isEmpty())
            return Mono.error(new ThrowException("MS012", "Machine type is required"));
        if (dto.getResetPeriod() == null || dto.getResetPeriod().isEmpty())
            return Mono.error(new ThrowException("MS013", "Reset period is required"));
        if (dto.getResponsiblePersonId() == null || dto.getResponsiblePersonId().isEmpty())
            return Mono.error(new ThrowException("MS014", "Responsible person is required"));
        if (dto.getMachineGroupId() == null || dto.getMachineGroupId().isEmpty())
            return Mono.error(new ThrowException("MS016", "Machine group is required"));
        if (dto.getRegisterId() != null)
            dto.setNote("REF:REGISTER-" + dto.getRegisterId());

        String prefix = dto.getMachineCode().substring(0, Math.min(8, dto.getMachineCode().length()));
        return template.select(Query.query(Criteria.where("machine_code").like(prefix + "%")), Machine.class)
                .collectList()
                .flatMap(existing -> {
                    if (!isUpdate) {
                        int max = existing.stream()
                                .map(Machine::getMachineCode)
                                .filter(c -> c.length() > 9)
                                .mapToInt(c -> { try { return Integer.parseInt(c.substring(9)); } catch (NumberFormatException e) { return 0; } })
                                .max().orElse(0);
                        String newCode = prefix + "-" + String.format("%04d", max + 1);
                        dto.setMachineCode(newCode);
                        dto.setCheckStatus("PENDING");
                        dto.setQrCode(String.format("{\"status\": true, \"code\": \"%s\"}", newCode));
                    }
                    return Mono.just(dto);
                })
                .doOnError(e -> log.error("Error in validateData", e));
    }

    // ─── BUILD ────────────────────────────────────────────────────────────────

    public Machine buildFromDTO(MachineDTO dto) {
        return Machine.builder()
                .calibration(dto.getIsCalibration() != null ? dto.getIsCalibration() : false)
                .checkStatus(dto.getCheckStatus())
                .cancelDate(dto.getCancelDate())
                .department(dto.getDepartment())
                .machineGroupId(dto.getMachineGroupId())
                .image(dto.getImage())
                .machineCode(dto.getMachineCode())
                .model(dto.getModel())
                .brand(dto.getBrand())
                .machineName(dto.getMachineName())
                .machineNumber(dto.getMachineNumber())
                .machineStatus(dto.getMachineStatus())
                .machineTypeId(dto.getMachineTypeId())
                .maintenancePeriod(dto.getMaintenancePeriod())
                .managerId(Long.valueOf(dto.getManagerId()))
                .qrCode(dto.getQrCode())
                .resetPeriod(dto.getResetPeriod())
                .responsiblePersonId(Long.valueOf(dto.getResponsiblePersonId()))
                .responsiblePersonName(dto.getResponsiblePersonName())
                .serialNumber(dto.getSerialNumber())
                .supervisorId(Long.valueOf(dto.getSupervisorId()))
                .workInstruction(dto.getWorkInstruction())
                .note(dto.getNote())
                .businessUnit(dto.getBusinessUnit())
                .registerId(dto.getRegisterId())
                .registerDate(dto.getRegisterDate())
                .certificatePeriod(dto.getCertificatePeriod())
                .reasonCancel(dto.getReasonCancel())
                .build();
    }

    private Update buildUpdateFromDTO(MachineDTO dto) {
        Map<SqlIdentifier, Object> p = new HashMap<>();
        addIfNotNull(p, "machine_code",            dto.getMachineCode());
        addIfNotNull(p, "calibration",             dto.getIsCalibration());
        addIfNotNull(p, "machine_name",            dto.getMachineName());
        addIfNotNull(p, "machine_number",          dto.getMachineNumber());
        addIfNotNull(p, "model",                   dto.getModel());
        addIfNotNull(p, "brand",                   dto.getBrand());
        addIfNotNull(p, "machine_type_id",         dto.getMachineTypeId());
        addIfNotNull(p, "serial_number",           dto.getSerialNumber());
        addIfNotNull(p, "department",              dto.getDepartment());
        addIfNotNull(p, "business_unit",           dto.getBusinessUnit());
        addIfNotNull(p, "machine_status",          dto.getMachineStatus());
        addIfNotNull(p, "check_status",            dto.getCheckStatus());
        addIfNotNull(p, "cancel_date",             dto.getCancelDate());
        addIfNotNull(p, "reason_cancel",           dto.getReasonCancel());
        addIfNotNull(p, "machine_group_id",        dto.getMachineGroupId());
        addIfNotNull(p, "maintenance_period",      dto.getMaintenancePeriod());
        addIfNotNull(p, "certificate_period",      dto.getCertificatePeriod());
        addIfNotNull(p, "reset_period",            dto.getResetPeriod());
        addIfNotNull(p, "manager_id",              dto.getManagerId());
        addIfNotNull(p, "responsible_person_id",   dto.getResponsiblePersonId());
        addIfNotNull(p, "responsible_person_name", dto.getResponsiblePersonName());
        addIfNotNull(p, "supervisor_id",           dto.getSupervisorId());
        addIfNotNull(p, "work_instruction",        dto.getWorkInstruction());
        addIfNotNull(p, "image",                   dto.getImage());
        addIfNotNull(p, "qr_code",                 dto.getQrCode());
        addIfNotNull(p, "register_id",             dto.getRegisterId());
        addIfNotNull(p, "register_date",           dto.getRegisterDate());
        addIfNotNull(p, "note",                    dto.getNote());
        return Update.from(p);
    }

    // ─── HELPERS ──────────────────────────────────────────────────────────────

    private Mono<Boolean> isMachineActive(String machineCode) {
        return template.select(
                        Query.query(Criteria.where("machine_code").is(machineCode)
                                .and("machine_status").in(ACTIVE_STATUSES)),
                        Machine.class)
                .next()
                .map(m -> !"MONTHLY".equals(m.getResetPeriod()))
                .defaultIfEmpty(false);
    }

    private long countFridaysInRange(LocalDate from, LocalDate to) {
        if (from.isAfter(to)) return 0;
        long count = 0;
        LocalDate d = from;
        while (!d.isAfter(to)) {
            if (d.getDayOfWeek() == DayOfWeek.FRIDAY) count++;
            d = d.plusDays(1);
        }
        return count;
    }

    private LocalDate clampStart(LocalDate from, LocalDate monthStart) {
        return from.isBefore(monthStart) ? monthStart : from;
    }

    private LocalDate clampEnd(LocalDate to, LocalDate cap) {
        return (to == null || to.isAfter(cap)) ? cap : to;
    }

    private LocalDate getLastFridayOfMonth(YearMonth ym) {
        LocalDate d = ym.atEndOfMonth();
        while (d.getDayOfWeek() != DayOfWeek.FRIDAY) d = d.minusDays(1);
        return d;
    }

    private Mono<String> resolveMemberName(Long memberId) {
        if (memberId == null) return Mono.just("");
        return template.selectOne(Query.query(Criteria.where("id").is(memberId)), Member.class)
                .map(m -> m.getFirstName() + " " + m.getLastName())
                .defaultIfEmpty("");
    }

    private Mono<MachineDTO> resolveDepartmentFields(MachineDTO dto) {
        if (dto.getDepartment() == null || dto.getDepartment().isBlank()) return Mono.just(dto);
        return template.selectOne(
                        Query.query(Criteria.where("department_code").is(dto.getDepartment())),
                        Department.class)
                .map(dept -> { dto.setBusinessUnit(dept.getBusinessUnit()); return dto; })
                .defaultIfEmpty(dto);
    }

    private Criteria buildKeywordCriteria(String keyword) {
        if (StringUtils.hasText(keyword))
            return Criteria.where("machine_name").like("%" + keyword + "%").ignoreCase(true)
                    .or("machine_code").like("%" + keyword + "%").ignoreCase(true);
        return Criteria.empty();
    }

    private Flux<MachineListDTO> convertMachineListDTOs(List<Machine> machines) {
        return Flux.fromIterable(machines).map(MachineListDTO::from);
    }

    private Mono<String> generateQRCodeReactive(String qrContent, String machineCode) {
        return Mono.fromCallable(() -> generateQRCodeSync(qrContent, machineCode))
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorMap(e -> new RuntimeException("Error generating QR code: " + e.getMessage(), e));
    }

    private String generateQRCodeSync(String qrContent, String machineCode) {
        try {
            int qrSize = 200, textAreaHeight = 30, totalHeight = qrSize + textAreaHeight;
            BitMatrix bitMatrix = new QRCodeWriter().encode(qrContent, BarcodeFormat.QR_CODE, qrSize, qrSize);
            BufferedImage img = new BufferedImage(qrSize, totalHeight, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = img.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setColor(Color.WHITE); g.fillRect(0, 0, qrSize, totalHeight);
            g.setColor(Color.BLACK);
            for (int x = 0; x < qrSize; x++)
                for (int y = 0; y < qrSize; y++)
                    if (bitMatrix.get(x, y)) g.fillRect(x, y, 1, 1);
            g.setFont(new Font("Arial", Font.BOLD, 12));
            FontMetrics fm = g.getFontMetrics();
            g.drawString(machineCode, (qrSize - fm.stringWidth(machineCode)) / 2, qrSize + (textAreaHeight / 2) + (fm.getAscent() / 2));
            g.dispose();
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(img, "PNG", baos);
            return "data:image/png;base64," + Base64.getEncoder().encodeToString(baos.toByteArray());
        } catch (WriterException | IOException e) {
            throw new RuntimeException("Failed to generate QR code", e);
        }
    }

    private void addIfNotNull(Map<SqlIdentifier, Object> params, String fieldName, Object value) {
        if (value != null) params.put(SqlIdentifier.quoted(fieldName), value);
    }

    private Mono<Void> postDeleteTask(List<String> names, Long memberId, Long departmentId) {
        return Mono.empty();
    }

    private Long getLongValue(io.r2dbc.spi.Row row, String columnName) {
        Object v = row.get(columnName);
        if (v == null) return 0L;
        if (v instanceof Long l) return l;
        if (v instanceof Number n) return n.longValue();
        return 0L;
    }
}