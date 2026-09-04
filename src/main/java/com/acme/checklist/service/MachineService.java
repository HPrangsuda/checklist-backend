package com.acme.checklist.service;

import com.acme.checklist.entity.*;
import com.acme.checklist.entity.enums.MachineStatus;
import com.acme.checklist.exception.ThrowException;
import com.acme.checklist.payload.ApiResponse;
import com.acme.checklist.payload.ListResponse;
import com.acme.checklist.payload.MemberPrincipal;
import com.acme.checklist.payload.PagedResponse;
import com.acme.checklist.payload.audit.AuditMemberDTO;
import com.acme.checklist.payload.calibration.CalibrationDTO;
import com.acme.checklist.payload.calibration.CalibrationResponseDTO;
import com.acme.checklist.payload.machine.FilterOptionsDTO;
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
import java.time.LocalDate;
import java.util.*;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class MachineService {

    private final R2dbcEntityTemplate template;
    private final CommonService commonService;
    private final KpiService kpiService;
    private final LarkService larkService;

    private static final List<String> ACTIVE_STATUSES = MachineStatus.activeDbValues();

    private boolean isNonActiveStatus(String status) {
        return status != null && !ACTIVE_STATUSES.contains(status);
    }

    private String nullSafe(String value) {
        return value != null ? value : "-";
    }

    private static String toDeptPrefix(String deptCode) {
        if (deptCode == null || deptCode.isBlank()) return null;
        if (deptCode.length() <= 1) return deptCode + "%";
        return deptCode.substring(0, deptCode.length() - 1) + "%";
    }

    private Mono<String> resolveDepartmentCodeByMemberId(Long memberId) {
        return template.selectOne(
                        Query.query(Criteria.where("id").is(memberId)),
                        Member.class)
                .map(Member::getDepartmentId)
                .defaultIfEmpty("");
    }

    // =========================================================================
    //  CREATE
    // =========================================================================

    public Mono<ApiResponse<Map<String, Object>>> create(MachineDTO dto) {
        dto.setId(null);
        return validateData(dto, false)
                .flatMap(validateDTO -> resolveDepartmentFields(validateDTO)
                        .flatMap(resolvedDTO -> {
                            Machine machine = buildFromDTO(resolvedDTO);
                            return commonService.save(machine, Machine.class)
                                    .flatMap(savedMachine -> createRelatedRecords(savedMachine, resolvedDTO)
                                            .then(Mono.just(savedMachine)))
                                    .flatMap(savedMachine ->
                                            notifyAdminsNewMachine(savedMachine)
                                                    .onErrorResume(e -> {
                                                        log.warn("Lark notify failed (non-blocking): {}", e.getMessage());
                                                        return Mono.empty();
                                                    })
                                                    .thenReturn(savedMachine))
                                    .map(savedMachine -> {
                                        Map<String, Object> result = new HashMap<>();
                                        result.put("id",          savedMachine.getId());
                                        result.put("machineCode", savedMachine.getMachineCode());
                                        return ApiResponse.<Map<String, Object>>success("MS001", result);
                                    });
                        }))
                .onErrorResume(ThrowException.class, e -> {
                    log.warn("Business validation failed during machine create: {}", e.getMessage());
                    return Mono.just(ApiResponse.<Map<String, Object>>error(e.getCode(), e.getMessage()));
                })
                .onErrorResume(e -> {
                    log.error("Unexpected error during machine create", e);
                    String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                    if (e instanceof NullPointerException) msg = "ข้อมูลบางส่วนเป็น null กรุณาตรวจสอบข้อมูล";
                    return Mono.just(ApiResponse.<Map<String, Object>>error("MS002", msg));
                });
    }

    private Mono<Void> createRelatedRecords(Machine machine, MachineDTO dto) {
        List<Mono<Void>> tasks = new ArrayList<>();
        if (dto.getCalibration() != null && dto.getCalibration().getDueDate() != null)
            tasks.add(createCalibrationRecord(machine, dto.getCalibration()));
        if (dto.getMaintenanceList() != null && !dto.getMaintenanceList().isEmpty())
            tasks.add(createMaintenanceRecords(machine, dto.getMaintenanceList()).then());

        if (machine.getResponsiblePersonId() != null) {
            Mono<Void> insertHistory = template
                    .exists(Query.query(
                                    Criteria.where("machine_code").is(machine.getMachineCode())
                                            .and("effective_to").isNull()),
                            ResponsibleHistory.class)
                    .flatMap(exists -> {
                        if (exists) {
                            log.warn("responsible_history open record already exists for machine={}, skipping insert",
                                    machine.getMachineCode());
                            return Mono.<Void>empty();
                        }
                        ResponsibleHistory history = ResponsibleHistory.builder()
                                .machineCode(machine.getMachineCode())
                                .responsiblePersonId(machine.getResponsiblePersonId())
                                .effectiveFrom(LocalDate.now())
                                .effectiveTo(null)
                                .build();
                        return template.insert(history).then();
                    })
                    .onErrorResume(org.springframework.dao.DuplicateKeyException.class, ex -> {
                        log.warn("responsible_history duplicate key for machine={}, skipping", machine.getMachineCode());
                        return Mono.empty();
                    });
            tasks.add(insertHistory);
        }

        return Mono.when(tasks);
    }

    private Mono<Void> createCalibrationRecord(Machine machine, CalibrationDTO dto) {
        CalibrationRecord cal = new CalibrationRecord();
        cal.setMachineCode(machine.getMachineCode());
        cal.setMachineName(machine.getMachineName());
        cal.setYears(String.valueOf(dto.getDueDate().getYear()));
        cal.setDueDate(dto.getDueDate());
        cal.setCertificateDate(dto.getCertificateDate());
        cal.setResults(dto.getResults());
        cal.setCriteria(dto.getCriteria());
        cal.setMeasuringRange(dto.getMeasuringRange());
        cal.setAccuracy(dto.getAccuracy());
        cal.setCalibrationRange(dto.getCalibrationRange());
        cal.setCalibrationStatus(dto.getCalibrationStatus());
        cal.setAttachment(dto.getAttachment());
        cal.setNote(dto.getNote());
        cal.setPermissibleCapacity(dto.getPermissibleCapacity());
        cal.setComment(dto.getComment());
        cal.setResolution(dto.getResolution());
        cal.setMaxUncertainty(dto.getMaxUncertainty());
        cal.setMpe(dto.getMpe());
        cal.setCheckMpe(dto.getCheckMpe());
        cal.setCheckResolution(dto.getCheckResolution());
        cal.setCheckResult(dto.getCheckResult());
        cal.setReasonNotPass(dto.getReasonNotPass());
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

    // =========================================================================
    //  UPDATE
    // =========================================================================

    public Mono<ApiResponse<Void>> update(MachineDTO machineDTO) {
        return validateData(machineDTO, true)
                .flatMap(v -> template.selectOne(
                                Query.query(Criteria.where("id").is(v.getId())),
                                Machine.class)
                        .switchIfEmpty(Mono.error(new ThrowException("MS004", "Machine not found with id: " + v.getId())))
                        .flatMap(existing -> {
                            Long    oldPersonId   = existing.getResponsiblePersonId();
                            Long    newPersonId   = v.getResponsiblePersonId();
                            String  newStatus     = v.getMachineStatus();
                            boolean nonActive     = isNonActiveStatus(newStatus);
                            boolean personChanged = newPersonId != null && !newPersonId.equals(oldPersonId);

                            if (nonActive) {
                                v.setCheckStatus("OUT OF SERVICE");
                                log.info("Machine {} set to non-active status '{}', forcing check_status=OUT OF SERVICE",
                                        existing.getMachineCode(), newStatus);
                            }

                            Machine snapshot = Machine.builder()
                                    .machineCode(existing.getMachineCode())
                                    .machineName(existing.getMachineName())
                                    .machineStatus(existing.getMachineStatus())
                                    .responsiblePersonName(existing.getResponsiblePersonName())
                                    .checkStatus(existing.getCheckStatus())
                                    .department(existing.getDepartment())
                                    .brand(existing.getBrand())
                                    .model(existing.getModel())
                                    .serialNumber(existing.getSerialNumber())
                                    .note(existing.getNote())
                                    .build();

                            Mono<Void> updateMachine = commonService.update(machineDTO.getId(), buildUpdateFromDTO(v), Machine.class).then();
                            Mono<Void> cancelRecords = nonActive ? cancelActiveRecords(existing.getMachineCode()) : Mono.empty();
                            Mono<Void> updateMaint   = nonActive ? Mono.empty() : updateMaintenanceRecords(existing.getMachineCode(), v);
                            Mono<Void> updateCal     = nonActive ? Mono.empty() : updateCalibrationRecord(existing.getMachineCode(), v);
                            Mono<Void> notify        = notifyMachineUpdate(snapshot, v, personChanged, newPersonId)
                                    .onErrorResume(e -> { log.warn("Notify update failed (non-blocking): {}", e.getMessage()); return Mono.empty(); });

                            if (!personChanged && !nonActive) {
                                return updateMachine.then(updateMaint).then(updateCal).then(cancelRecords).then(notify)
                                        .then(Mono.just(ApiResponse.<Void>success("MS003")));
                            }

                            LocalDate today     = LocalDate.now();
                            LocalDate yesterday = today.minusDays(1);

                            Mono<Void> closeOld = template.update(
                                    Query.query(Criteria.where("machine_code").is(existing.getMachineCode()).and("effective_to").isNull()),
                                    Update.update("effective_to", yesterday),
                                    ResponsibleHistory.class).then();

                            Mono<Void> insertNew = Mono.empty();
                            if (personChanged && !nonActive) {
                                ResponsibleHistory newHistory = ResponsibleHistory.builder()
                                        .machineCode(existing.getMachineCode())
                                        .responsiblePersonId(newPersonId)
                                        .effectiveFrom(today)
                                        .effectiveTo(null)
                                        .build();
                                insertNew = template.insert(newHistory).then();
                            }

                            Mono<Void> kpiOld = kpiService.recalculateKpiForPerson(oldPersonId);
                            Mono<Void> kpiNew = (personChanged && !nonActive)
                                    ? kpiService.recalculateKpiForPerson(newPersonId) : Mono.empty();

                            return updateMachine.then(updateMaint).then(updateCal).then(cancelRecords)
                                    .then(closeOld).then(insertNew).then(kpiOld).then(kpiNew).then(notify)
                                    .then(Mono.just(ApiResponse.<Void>success("MS003")));
                        }))
                .onErrorResume(ThrowException.class, e -> {
                    log.warn("Business validation failed during machine update: {}", e.getMessage());
                    return Mono.just(ApiResponse.<Void>error(e.getCode(), e.getMessage()));
                })
                .onErrorResume(e -> {
                    log.error("Failed to update the machine: {}", e.getMessage(), e);
                    return Mono.just(ApiResponse.<Void>error("MS004", e.getMessage()));
                });
    }

    private Mono<Void> updateMaintenanceRecords(String machineCode, MachineDTO dto) {
        if (dto.getMaintenanceList() == null || dto.getMaintenanceList().isEmpty()) {
            log.debug("No maintenanceList in DTO for machine={}, skipping maintenance update", machineCode);
            return Mono.empty();
        }
        Criteria deleteCriteria = Criteria.where("machine_code").is(machineCode)
                .and("actual_date").isNull()
                .and(Criteria.where("is_canceled").isNull().or("is_canceled").is(false));
        Machine machineRef = Machine.builder().machineCode(machineCode).machineName(dto.getMachineName()).build();
        return template.delete(Query.query(deleteCriteria), MaintenanceRecord.class)
                .doOnSuccess(count -> log.info("Deleted {} pending maintenance records for machine={}", count, machineCode))
                .then(createMaintenanceRecords(machineRef, dto.getMaintenanceList()))
                .onErrorResume(e -> {
                    log.error("Failed to update maintenance records for machine={}: {}", machineCode, e.getMessage(), e);
                    return Mono.empty();
                });
    }

    private Mono<Void> updateCalibrationRecord(String machineCode, MachineDTO dto) {
        if (dto.getCalibration() == null || dto.getCalibration().getDueDate() == null) {
            log.debug("No calibration in DTO for machine={}, skipping calibration update", machineCode);
            return Mono.empty();
        }
        CalibrationDTO cal = dto.getCalibration();

        if (cal.getId() != null) {
            var spec = template.getDatabaseClient()
                    .sql("""
                        UPDATE calibration_record SET
                            due_date           = $1,
                            certificate_date   = $2,
                            results            = $3,
                            criteria           = $4,
                            measuring_range    = $5,
                            accuracy           = $6,
                            calibration_range  = $7,
                            calibration_status = $8,
                            note               = $9,
                            years              = $10
                        WHERE id = $11 AND machine_code = $12
                    """)
                    .bind(0, cal.getDueDate());
            spec = cal.getCertificateDate()   != null ? spec.bind(1, cal.getCertificateDate())   : spec.bindNull(1, LocalDate.class);
            spec = cal.getResults()           != null ? spec.bind(2, cal.getResults())           : spec.bindNull(2, String.class);
            spec = cal.getCriteria()          != null ? spec.bind(3, cal.getCriteria())          : spec.bindNull(3, String.class);
            spec = cal.getMeasuringRange()    != null ? spec.bind(4, cal.getMeasuringRange())    : spec.bindNull(4, String.class);
            spec = cal.getAccuracy()          != null ? spec.bind(5, cal.getAccuracy())          : spec.bindNull(5, String.class);
            spec = cal.getCalibrationRange()  != null ? spec.bind(6, cal.getCalibrationRange())  : spec.bindNull(6, String.class);
            spec = cal.getCalibrationStatus() != null ? spec.bind(7, cal.getCalibrationStatus()) : spec.bindNull(7, String.class);
            spec = cal.getNote()              != null ? spec.bind(8, cal.getNote())              : spec.bindNull(8, String.class);
            return spec
                    .bind(9,  String.valueOf(cal.getDueDate().getYear()))
                    .bind(10, cal.getId())
                    .bind(11, machineCode)
                    .then()
                    .onErrorResume(e -> {
                        log.error("Failed to update calibration id={} for machine={}: {}", cal.getId(), machineCode, e.getMessage(), e);
                        return Mono.empty();
                    });
        }

        Machine machineRef = Machine.builder().machineCode(machineCode).machineName(dto.getMachineName()).build();
        return template.selectOne(
                        Query.query(Criteria.where("machine_code").is(machineCode))
                                .sort(Sort.by(Sort.Direction.DESC, "id")),
                        CalibrationRecord.class)
                .flatMap(existing -> {
                    existing.setDueDate(cal.getDueDate());
                    existing.setCertificateDate(cal.getCertificateDate());
                    existing.setResults(cal.getResults());
                    existing.setCriteria(cal.getCriteria());
                    existing.setMeasuringRange(cal.getMeasuringRange());
                    existing.setAccuracy(cal.getAccuracy());
                    existing.setCalibrationRange(cal.getCalibrationRange());
                    existing.setCalibrationStatus(cal.getCalibrationStatus());
                    existing.setNote(cal.getNote());
                    existing.setYears(String.valueOf(cal.getDueDate().getYear()));
                    return template.update(existing).then();
                })
                .switchIfEmpty(createCalibrationRecord(machineRef, cal))
                .onErrorResume(e -> {
                    log.error("Failed to upsert calibration for machine={}: {}", machineCode, e.getMessage(), e);
                    return Mono.empty();
                });
    }

    private Mono<Void> cancelActiveRecords(String machineCode) {
        LocalDate today = LocalDate.now();
        Mono<Void> cancelCal = template.getDatabaseClient()
                .sql("""
                    UPDATE calibration_record
                    SET is_canceled = TRUE, canceled_at = $1
                    WHERE machine_code = $2
                      AND certificate_date IS NULL
                      AND (is_canceled = FALSE OR is_canceled IS NULL)
                """)
                .bind(0, today).bind(1, machineCode).then();
        Mono<Void> cancelMaint = template.getDatabaseClient()
                .sql("""
                    UPDATE maintenance_record
                    SET is_canceled = TRUE, canceled_at = $1
                    WHERE machine_code = $2
                      AND actual_date IS NULL
                      AND (is_canceled = FALSE OR is_canceled IS NULL)
                """)
                .bind(0, today).bind(1, machineCode).then();
        return cancelCal.then(cancelMaint)
                .doOnSuccess(v -> log.info("Canceled active records for machine: {}", machineCode))
                .doOnError(e -> log.error("Failed to cancel records for machine {}: {}", machineCode, e.getMessage()));
    }

    // =========================================================================
    //  DELETE
    // =========================================================================

    public Mono<ApiResponse<Void>> delete(List<Long> ids) {
        return commonService.auditContext()
                .flatMap(ctx -> commonService.deleteEntitiesByIds(
                        ids, Machine.class, "MS005", "MS006", "MS007",
                        Machine::getMachineName,
                        names -> postDeleteTask(names, ctx.get("X-Member-Id"), ctx.get("X-Department-Id"))));
    }

    // =========================================================================
    //  CHANGE RESPONSIBLE PERSON
    // =========================================================================

    public Mono<ApiResponse<Void>> changeResponsiblePerson(String machineCode, Long newPersonId) {
        LocalDate today     = LocalDate.now();
        LocalDate yesterday = today.minusDays(1);
        return template.selectOne(Query.query(Criteria.where("machine_code").is(machineCode)), Machine.class)
                .switchIfEmpty(Mono.error(new ThrowException("MS030", "Machine not found: " + machineCode)))
                .flatMap(machine -> {
                    Long oldPersonId = machine.getResponsiblePersonId();
                    Mono<Void> closeOld = template.update(
                            Query.query(Criteria.where("machine_code").is(machineCode).and("effective_to").isNull()),
                            Update.update("effective_to", yesterday), ResponsibleHistory.class).then();
                    ResponsibleHistory newHistory = ResponsibleHistory.builder()
                            .machineCode(machineCode).responsiblePersonId(newPersonId).effectiveFrom(today).build();
                    Mono<Void> insertNew     = template.insert(newHistory).then();
                    machine.setResponsiblePersonId(newPersonId);
                    Mono<Void> updateMachine = template.update(machine).then();
                    return closeOld.then(insertNew).then(updateMachine)
                            .then(kpiService.recalculateKpiForPerson(oldPersonId))
                            .then(kpiService.recalculateKpiForPerson(newPersonId))
                            .then(Mono.just(ApiResponse.<Void>success("MS031")));
                })
                .onErrorResume(ThrowException.class, e -> Mono.just(ApiResponse.<Void>error(e.getCode(), e.getMessage())))
                .onErrorResume(e -> { log.error("Failed to change responsible person: {}", e.getMessage()); return Mono.just(ApiResponse.<Void>error("MS032", e.getMessage())); });
    }

    // =========================================================================
    //  GET BY ROLE
    // =========================================================================

    public Mono<PagedResponse<MachineListDTO>> getByRole(
            String keyword, int index, int size, boolean mine,
            String checkStatus, String department, String machineStatus, String responsiblePersonName) {

        return ReactiveSecurityContextHolder.getContext()
                .mapNotNull(ctx -> (MemberPrincipal) Objects.requireNonNull(ctx.getAuthentication()).getPrincipal())
                .flatMap(principal -> {
                    String role     = principal.role();
                    Long   memberId = principal.memberId();
                    log.info(">>> getByRole role='{}' memberId={}", role, memberId);

                    if ("DEPARTMENT_ADMIN".equals(role)) {
                        return resolveDepartmentCodeByMemberId(memberId)
                                .flatMap(deptCode -> {
                                    log.info(">>> DEPARTMENT_ADMIN deptCode='{}' mine={}", deptCode, mine);
                                    Criteria base          = buildDepartmentAdminCriteria(deptCode, memberId, mine);
                                    Criteria finalCriteria = applyKeywordAndFilters(base, keyword, checkStatus, department, machineStatus, responsiblePersonName);
                                    Query    query         = Query.query(finalCriteria).with(commonService.pageable(index, size, "created_at"));
                                    return commonService.executePagedQuery(index, size, query, finalCriteria, Machine.class, this::convertMachineListDTOs);
                                });
                    }

                    Criteria base          = buildRoleBaseCriteria(role, memberId, mine);
                    Criteria finalCriteria = applyKeywordAndFilters(base, keyword, checkStatus, department, machineStatus, responsiblePersonName);
                    Query    query         = Query.query(finalCriteria).with(commonService.pageable(index, size, "created_at"));
                    return commonService.executePagedQuery(index, size, query, finalCriteria, Machine.class, this::convertMachineListDTOs);
                });
    }

    private Criteria buildDepartmentAdminCriteria(String departmentCode, Long memberId, boolean mine) {
        if (!StringUtils.hasText(departmentCode)) {
            log.warn("DEPARTMENT_ADMIN has no departmentCode, returning no-match criteria");
            return Criteria.where("department").is("__NO_MATCH__");
        }
        String prefix = toDeptPrefix(departmentCode);
        Criteria base = Criteria.where("department").like(prefix)
                .and(Criteria.where("machine_status").in(ACTIVE_STATUSES));
        if (mine) return base.and(Criteria.where("responsible_person_id").is(memberId));
        return base;
    }

    private Criteria buildRoleBaseCriteria(String role, Long memberId, boolean mine) {
        return switch (role) {
            case "ADMIN" -> Criteria.empty();
            case "MANAGER" -> {
                Criteria active = Criteria.where("machine_status").in(ACTIVE_STATUSES);
                yield mine
                        ? active.and(Criteria.where("responsible_person_id").is(memberId))
                        : active.and(Criteria.where("responsible_person_id").is(memberId).or("manager_id").is(memberId));
            }
            case "SUPERVISOR" -> {
                Criteria active = Criteria.where("machine_status").in(ACTIVE_STATUSES);
                yield mine
                        ? active.and(Criteria.where("responsible_person_id").is(memberId))
                        : active.and(Criteria.where("responsible_person_id").is(memberId).or("supervisor_id").is(memberId));
            }
            default -> Criteria.where("machine_status").in(ACTIVE_STATUSES)
                    .and(Criteria.where("responsible_person_id").is(memberId));
        };
    }

    private Criteria applyKeywordAndFilters(Criteria base, String keyword, String checkStatus,
                                            String department, String machineStatus, String responsiblePersonName) {
        Criteria criteria = base;
        if (StringUtils.hasText(keyword)) {
            String kw = "%" + keyword.trim() + "%";
            criteria = criteria.and(Criteria.where("machine_name").like(kw).ignoreCase(true)
                    .or("machine_code").like(kw).ignoreCase(true)
                    .or("responsible_person_name").like(kw).ignoreCase(true));
        }
        if (StringUtils.hasText(department))            criteria = criteria.and(Criteria.where("department").is(department));
        if (StringUtils.hasText(machineStatus))         criteria = criteria.and(Criteria.where("machine_status").is(machineStatus));
        if (StringUtils.hasText(checkStatus))           criteria = criteria.and(Criteria.where("check_status").is(checkStatus));
        if (StringUtils.hasText(responsiblePersonName)) criteria = criteria.and(Criteria.where("responsible_person_name").is(responsiblePersonName));
        return criteria;
    }

    // =========================================================================
    //  FILTER OPTIONS
    // =========================================================================

    public Mono<ApiResponse<FilterOptionsDTO>> getFilterOptions() {
        return ReactiveSecurityContextHolder.getContext()
                .mapNotNull(ctx -> (MemberPrincipal) Objects.requireNonNull(ctx.getAuthentication()).getPrincipal())
                .flatMap(principal -> {
                    String role     = principal.role();
                    Long   memberId = principal.memberId();

                    if ("DEPARTMENT_ADMIN".equals(role)) {
                        return resolveDepartmentCodeByMemberId(memberId)
                                .flatMap(deptCode -> {
                                    String prefix = toDeptPrefix(deptCode);
                                    String filter = prefix != null ? "AND m.department LIKE '" + prefix + "'" : "AND 1=0";
                                    return buildFilterOptions(filter);
                                });
                    }

                    String roleFilter = switch (role) {
                        case "ADMIN"      -> "";
                        case "MANAGER"    -> "AND (m.responsible_person_id = " + memberId + " OR m.manager_id = " + memberId + ")";
                        case "SUPERVISOR" -> "AND (m.responsible_person_id = " + memberId + " OR m.supervisor_id = " + memberId + ")";
                        default           -> "AND m.responsible_person_id = " + memberId;
                    };
                    return buildFilterOptions(roleFilter);
                })
                .onErrorResume(e -> {
                    log.error("Failed to fetch filter options: {}", e.getMessage());
                    return Mono.just(ApiResponse.error("MS041", e.getMessage()));
                });
    }

    private Mono<ApiResponse<FilterOptionsDTO>> buildFilterOptions(String roleFilter) {
        String activeInClause = MachineStatus.sqlInClause();
        String sql = """
                SELECT DISTINCT
                    m.department,
                    d.department       AS department_name,
                    d.division         AS division,
                    m.machine_status,
                    m.check_status,
                    m.responsible_person_name
                FROM machine m
                LEFT JOIN department d ON m.department = d.department_code
                WHERE m.machine_status IN (%s)
                %s
                ORDER BY m.department, m.machine_status, m.check_status, m.responsible_person_name
                """.formatted(activeInClause, roleFilter);

        return template.getDatabaseClient().sql(sql)
                .map((row, meta) -> new Object[]{
                        row.get("department",              String.class),
                        row.get("department_name",         String.class),
                        row.get("division",                String.class),
                        row.get("machine_status",          String.class),
                        row.get("check_status",            String.class),
                        row.get("responsible_person_name", String.class)
                })
                .all().collectList()
                .map(rows -> {
                    Map<String, String> deptMap            = new LinkedHashMap<>();
                    Set<String>         machineStatuses    = new LinkedHashSet<>();
                    Set<String>         checkStatuses      = new LinkedHashSet<>();
                    Set<String>         responsiblePersons = new LinkedHashSet<>();
                    for (Object[] row : rows) {
                        String deptCode = (String) row[0];
                        String deptName = (String) row[1];
                        String division = (String) row[2];
                        if (deptCode != null && deptName != null) deptMap.putIfAbsent(deptCode, buildDeptLabel(deptName, division));
                        if (StringUtils.hasText((String) row[3])) machineStatuses.add((String) row[3]);
                        if (StringUtils.hasText((String) row[4])) checkStatuses.add((String) row[4]);
                        if (StringUtils.hasText((String) row[5])) responsiblePersons.add((String) row[5]);
                    }
                    List<Map<String, String>> departments = deptMap.entrySet().stream()
                            .map(e -> Map.of("code", e.getKey(), "name", e.getValue())).toList();
                    FilterOptionsDTO dto = new FilterOptionsDTO();
                    dto.setDepartments(departments);
                    dto.setMachineStatuses(new ArrayList<>(machineStatuses));
                    dto.setCheckStatuses(new ArrayList<>(checkStatuses));
                    dto.setResponsiblePersons(new ArrayList<>(responsiblePersons));
                    return ApiResponse.success("MS040", dto);
                });
    }

    public Flux<MachineSummaryDTO> getDepartmentSummaryWithRole() {
        return ReactiveSecurityContextHolder.getContext()
                .mapNotNull(ctx -> (MemberPrincipal) Objects.requireNonNull(ctx.getAuthentication()).getPrincipal())
                .flatMapMany(principal -> {
                    String role     = principal.role();
                    Long   memberId = principal.memberId();

                    if ("DEPARTMENT_ADMIN".equals(role)) {
                        return resolveDepartmentCodeByMemberId(memberId)
                                .flatMapMany(deptCode -> {
                                    String prefix = toDeptPrefix(deptCode);
                                    String filter = prefix != null ? "AND m.department LIKE '" + prefix + "'" : "AND 1=0";
                                    return buildDepartmentSummary(filter);
                                });
                    }

                    String roleFilter = switch (role) {
                        case "ADMIN"      -> "";
                        case "MANAGER"    -> "AND (m.responsible_person_id = " + memberId + " OR m.manager_id = " + memberId + ")";
                        case "SUPERVISOR" -> "AND (m.responsible_person_id = " + memberId + " OR m.supervisor_id = " + memberId + ")";
                        default           -> "AND m.responsible_person_id = " + memberId;
                    };
                    return buildDepartmentSummary(roleFilter);
                });
    }

    private Flux<MachineSummaryDTO> buildDepartmentSummary(String roleFilter) {
        String activeInClause = MachineStatus.sqlInClause();
        String sql = """
                SELECT
                    d.department_code,
                    d.department as department_name,
                    COUNT(m.id) as total,
                    COUNT(CASE WHEN m.machine_status = 'OPERATIONAL'       THEN 1 END) as total_ready_to_use,
                    COUNT(CASE WHEN m.machine_status = 'UNDER MAINTENANCE' THEN 1 END) as total_not_in_use,
                    COUNT(CASE WHEN UPPER(m.check_status) = 'COMPLETED'      THEN 1 END) as total_completed,
                    COUNT(CASE WHEN UPPER(m.check_status) LIKE '%%PENDING%%' THEN 1 END) as total_pending,
                    COUNT(CASE WHEN UPPER(m.check_status) = 'APPROVE'        THEN 1 END) as total_approve
                FROM machine m
                JOIN department d ON m.department = d.department_code
                WHERE m.machine_status IN (%s) %s
                GROUP BY d.department_code, d.department
                ORDER BY d.department
                """.formatted(activeInClause, roleFilter);

        return template.getDatabaseClient().sql(sql)
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
                            .total(total).totalReadyToUse(readyToUse).totalRepair(repair).totalNotInUse(notInUse)
                            .totalCompleted(completed).totalPending(pending).totalApprove(approve)
                            .readyRate(total > 0 ? (readyToUse * 100.0) / total : 0)
                            .completedRate(total > 0 ? (completed * 100.0) / total : 0)
                            .approveRate(total > 0 ? (approve * 100.0) / total : 0)
                            .build();
                })
                .all()
                .onErrorResume(e -> { log.error("Error fetching machine department summary with role", e); return Flux.empty(); });
    }

    // =========================================================================
    //  GET LIST / GET BY ID / GET BY MACHINE CODE
    // =========================================================================

    public Mono<ListResponse<List<MachineListDTO>>> getList(String keyword, List<Long> ids, int index, int size) {
        Pageable pageable = PageRequest.of(index, size, Sort.by(Sort.Direction.DESC, "id"));
        boolean  hasIds   = ids != null && !ids.isEmpty();
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

    public Mono<ApiResponse<MachineResponseDTO>> getById(Long id) {
        return template.selectOne(Query.query(Criteria.where("id").is(id)), Machine.class)
                .switchIfEmpty(Mono.error(new ThrowException("MS018", "Machine not found with id: " + id)))
                .flatMap(machine -> {
                    String machineCode  = machine.getMachineCode();
                    String machineGroup = machine.getMachineGroupId();
                    String machineType  = machine.getMachineTypeId();
                    String deptCode     = machine.getDepartment();

                    if (machine.getQrCode() == null || machine.getQrCode().isEmpty())
                        return Mono.just(ApiResponse.<MachineResponseDTO>error("MS020", "QR code data is missing"));

                    Mono<List<CalibrationRecord>> calibMono = template.select(
                            Query.query(Criteria.where("machine_code").is(machineCode)).sort(Sort.by(Sort.Direction.DESC, "id")),
                            CalibrationRecord.class).collectList();
                    Mono<List<MaintenanceRecord>> maintMono = template.select(
                            Query.query(Criteria.where("machine_code").is(machineCode)).sort(Sort.by(Sort.Direction.ASC, "round")),
                            MaintenanceRecord.class).collectList();

                    List<Long> auditIds = new ArrayList<>();
                    if (machine.getCreatedBy() != null) auditIds.add(machine.getCreatedBy());
                    if (machine.getUpdatedBy() != null) auditIds.add(machine.getUpdatedBy());
                    Mono<Map<Long, Member>> auditMono    = auditIds.isEmpty() ? Mono.just(new HashMap<>()) : commonService.fetchMembersByIds(auditIds);
                    Mono<String>            qrMono       = generateQRCodeReactive(machine.getQrCode(), machineCode);
                    Mono<String>            supNameMono  = resolveMemberName(machine.getSupervisorId());
                    Mono<String>            mgrNameMono  = resolveMemberName(machine.getManagerId());

                    Mono<String> groupNameMono = Mono.justOrEmpty(machineGroup)
                            .flatMap(gid -> template.getDatabaseClient()
                                    .sql("SELECT machine_group_name FROM machine_type WHERE machine_group_id = $1 LIMIT 1")
                                    .bind("$1", gid).map((row, meta) -> row.get("machine_group_name", String.class)).first())
                            .defaultIfEmpty(machineGroup != null ? machineGroup : "");

                    Mono<String> typeNameMono = Mono.justOrEmpty(machineType)
                            .flatMap(tid -> {
                                assert machineGroup != null;
                                return template.getDatabaseClient()
                                        .sql("SELECT machine_type_name FROM machine_type WHERE machine_group_id = $1 AND machine_type_id = $2 LIMIT 1")
                                        .bind("$1", machineGroup).bind("$2", tid).map((row, meta) -> row.get("machine_type_name", String.class)).first();
                            })
                            .defaultIfEmpty(machineType != null ? machineType : "");

                    Mono<String> deptNameMono = Mono.justOrEmpty(deptCode)
                            .flatMap(code -> template.selectOne(Query.query(Criteria.where("department_code").is(code)), Department.class)
                                    .map(dept -> buildDeptLabel(dept.getDepartment(), dept.getDivision())))
                            .defaultIfEmpty(deptCode != null ? deptCode : "");

                    return Mono.zip(calibMono, maintMono, auditMono, qrMono, supNameMono, mgrNameMono)
                            .flatMap(t6 -> Mono.zip(groupNameMono, typeNameMono, deptNameMono)
                                    .map(t3 -> {
                                        MachineResponseDTO dto = MachineResponseDTO.from(machine,
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
                .onErrorResume(ThrowException.class, e -> Mono.just(ApiResponse.error(e.getCode(), e.getMessage())))
                .onErrorResume(e -> { log.error("Failed to fetch machine {}: {}", id, e.getMessage(), e); return Mono.just(ApiResponse.error("MS019", e.getMessage() != null ? e.getMessage() : "Unknown error")); });
    }

    public Mono<ApiResponse<Machine>> getByMachineCode(String machineCode) {
        return template.selectOne(
                        Query.query(Criteria.where("machine_code").is(machineCode)
                                .and("machine_status").is(MachineStatus.OPERATIONAL.getDbValue())),
                        Machine.class)
                .map(m -> ApiResponse.success("MS017", m))
                .switchIfEmpty(Mono.just(ApiResponse.error("MS018", "Machine not found or not operational: " + machineCode)))
                .onErrorResume(e -> Mono.just(ApiResponse.error("MS019", e.getMessage() != null ? e.getMessage() : "Unknown error")));
    }

    public Flux<Machine> getAll() {
        return template.select(Query.empty().sort(Sort.by(Sort.Direction.ASC, "id")), Machine.class);
    }

    public Mono<Machine> getMachineById(Long id) {
        return template.selectOne(Query.query(Criteria.where("id").is(id)), Machine.class);
    }

    // =========================================================================
    //  VALIDATE / BUILD
    // =========================================================================

    public Mono<MachineDTO> validateData(MachineDTO dto, boolean isUpdate) {
        if (dto.getDepartment()          == null || dto.getDepartment().isEmpty())     return Mono.error(new ThrowException("MS008", "Department is required"));
        if (dto.getMachineCode()         == null || dto.getMachineCode().isEmpty())    return Mono.error(new ThrowException("MS009", "Machine code is required"));
        if (dto.getMachineName()         == null || dto.getMachineName().isEmpty())    return Mono.error(new ThrowException("MS010", "Machine name is required"));
        if (dto.getMachineStatus()       == null || dto.getMachineStatus().isEmpty())  return Mono.error(new ThrowException("MS011", "Machine status is required"));
        if (dto.getMachineTypeId()       == null || dto.getMachineTypeId().isEmpty())  return Mono.error(new ThrowException("MS012", "Machine type is required"));
        if (dto.getResetPeriod()         == null || dto.getResetPeriod().isEmpty())    return Mono.error(new ThrowException("MS013", "Reset period is required"));
        if (dto.getResponsiblePersonId() == null)                                      return Mono.error(new ThrowException("MS014", "Responsible person is required"));
        if (dto.getMachineGroupId()      == null || dto.getMachineGroupId().isEmpty()) return Mono.error(new ThrowException("MS016", "Machine group is required"));
        if (dto.getRegisterId() != null) dto.setNote("REF:REGISTER-" + dto.getRegisterId());

        String prefix = dto.getMachineCode().substring(0, Math.min(8, dto.getMachineCode().length()));
        return template.select(Query.query(Criteria.where("machine_code").like(prefix + "%")), Machine.class)
                .collectList()
                .flatMap(existing -> {
                    if (!isUpdate) {
                        int max = existing.stream()
                                .map(Machine::getMachineCode).filter(c -> c.length() > 9)
                                .mapToInt(c -> { try { return Integer.parseInt(c.substring(9)); } catch (NumberFormatException e) { return 0; } })
                                .max().orElse(0);
                        String newCode = prefix + "-" + String.format("%04d", max + 1);
                        dto.setMachineCode(newCode);
                        dto.setCheckStatus("PENDING");
                        dto.setQrCode(String.format("{\"status\": true, \"code\": \"%s\"}", newCode));
                    }
                    return Mono.just(dto);
                });
    }

    public Machine buildFromDTO(MachineDTO dto) {
        return Machine.builder()
                .calibration(dto.getIsCalibration() != null ? dto.getIsCalibration() : false)
                .checkStatus(dto.getCheckStatus()).cancelDate(dto.getCancelDate()).department(dto.getDepartment())
                .machineGroupId(dto.getMachineGroupId()).image(dto.getImage()).machineCode(dto.getMachineCode())
                .model(dto.getModel()).brand(dto.getBrand()).machineName(dto.getMachineName())
                .machineNumber(dto.getMachineNumber()).machineStatus(dto.getMachineStatus())
                .machineTypeId(dto.getMachineTypeId()).maintenancePeriod(dto.getMaintenancePeriod())
                .managerId(dto.getManagerId()).qrCode(dto.getQrCode()).resetPeriod(dto.getResetPeriod())
                .responsiblePersonId(dto.getResponsiblePersonId()).responsiblePersonName(dto.getResponsiblePersonName())
                .serialNumber(dto.getSerialNumber()).supervisorId(dto.getSupervisorId())
                .workInstruction(dto.getWorkInstruction()).note(dto.getNote()).businessUnit(dto.getBusinessUnit())
                .registerId(dto.getRegisterId()).registerDate(dto.getRegisterDate())
                .certificatePeriod(dto.getCertificatePeriod()).reasonCancel(dto.getReasonCancel())
                .hasWarranty(dto.getHasWarranty())
                .warrantyNote("YES".equals(dto.getHasWarranty()) ? dto.getWarrantyNote() : null)
                .warrantyExpireDate("YES".equals(dto.getHasWarranty()) && dto.getWarrantyExpireDate() != null
                        ? dto.getWarrantyExpireDate().toLocalDate() : null)
                .warrantyFiles("YES".equals(dto.getHasWarranty()) ? dto.getWarrantyFiles() : null)
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
        p.put(SqlIdentifier.quoted("cancel_date"),   dto.getCancelDate());
        p.put(SqlIdentifier.quoted("reason_cancel"), dto.getReasonCancel());
        addIfNotNull(p, "machine_group_id",        dto.getMachineGroupId());
        addIfNotNull(p, "maintenance_period",      dto.getMaintenancePeriod());
        addIfNotNull(p, "certificate_period",      dto.getCertificatePeriod());
        addIfNotNull(p, "reset_period",            dto.getResetPeriod());
        addIfNotNull(p, "responsible_person_name", dto.getResponsiblePersonName());
        addIfNotNull(p, "work_instruction",        dto.getWorkInstruction());
        addIfNotNull(p, "register_id",             dto.getRegisterId());
        addIfNotNull(p, "register_date",           dto.getRegisterDate());
        addIfNotNull(p, "note",                    dto.getNote());
        addIfNotNull(p, "has_warranty",            dto.getHasWarranty());
        if (dto.getCheckStatus() != null) p.put(SqlIdentifier.quoted("check_status"), dto.getCheckStatus());
        p.put(SqlIdentifier.quoted("updated_at"),           java.time.LocalDateTime.now());
        p.put(SqlIdentifier.quoted("warranty_note"),        "YES".equals(dto.getHasWarranty()) ? dto.getWarrantyNote() : null);
        p.put(SqlIdentifier.quoted("warranty_expire_date"), "YES".equals(dto.getHasWarranty()) && dto.getWarrantyExpireDate() != null ? dto.getWarrantyExpireDate().toLocalDate() : null);
        p.put(SqlIdentifier.quoted("warranty_files"),       "YES".equals(dto.getHasWarranty()) ? dto.getWarrantyFiles() : null);
        p.put(SqlIdentifier.quoted("responsible_person_id"), dto.getResponsiblePersonId());
        p.put(SqlIdentifier.quoted("supervisor_id"),         dto.getSupervisorId());
        p.put(SqlIdentifier.quoted("manager_id"),            dto.getManagerId());
        String imageVal = dto.getImage();
        p.put(SqlIdentifier.quoted("image"), imageVal != null && imageVal.isBlank() ? null : imageVal);
        String wiVal = dto.getWorkInstruction();
        p.put(SqlIdentifier.quoted("work_instruction"), wiVal != null && wiVal.isBlank() ? null : wiVal);
        return Update.from(p);
    }

    // =========================================================================
    //  PRIVATE HELPERS
    // =========================================================================

    private String buildDeptLabel(String deptName, String division) {
        if (deptName == null) return "";
        if (StringUtils.hasText(division)) return deptName + " - " + division.trim();
        return deptName;
    }

    private Mono<String> resolveMemberName(Long memberId) {
        if (memberId == null) return Mono.just("");
        return template.selectOne(Query.query(Criteria.where("id").is(memberId)), Member.class)
                .map(m -> m.getFirstName() + " " + m.getLastName()).defaultIfEmpty("");
    }

    private Mono<MachineDTO> resolveDepartmentFields(MachineDTO dto) {
        if (dto.getDepartment() == null || dto.getDepartment().isBlank()) return Mono.just(dto);
        return template.selectOne(Query.query(Criteria.where("department_code").is(dto.getDepartment())), Department.class)
                .map(dept -> { dto.setBusinessUnit(dept.getBusinessUnit()); return dto; })
                .defaultIfEmpty(dto);
    }

    private Flux<MachineListDTO> convertMachineListDTOs(List<Machine> machines) {
        List<String> codes = machines.stream().map(Machine::getDepartment).filter(Objects::nonNull).distinct().toList();
        if (codes.isEmpty()) return Flux.fromIterable(machines).map(machine -> MachineListDTO.from(machine, ""));
        return template.select(Query.query(Criteria.where("department_code").in(codes)), Department.class)
                .collectMap(Department::getDepartmentCode, dept -> buildDeptLabel(dept.getDepartment(), dept.getDivision()))
                .flatMapMany(deptMap -> Flux.fromIterable(machines)
                        .map(machine -> {
                            String label = deptMap.getOrDefault(machine.getDepartment(), machine.getDepartment() != null ? machine.getDepartment() : "");
                            return MachineListDTO.from(machine, label);
                        }));
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
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,      RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setColor(Color.WHITE); g.fillRect(0, 0, qrSize, totalHeight);
            g.setColor(Color.BLACK);
            for (int x = 0; x < qrSize; x++)
                for (int y = 0; y < qrSize; y++)
                    if (bitMatrix.get(x, y)) g.fillRect(x, y, 1, 1);
            Font font = new Font(Font.SANS_SERIF, Font.BOLD, 12);
            g.setFont(font);
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

    private Mono<Void> notifyAdminsNewMachine(Machine machine) {
        return template.select(Query.query(Criteria.where("role_type").is("ADMIN").and("status").is("ACTIVE")), Member.class)
                .collectList()
                .flatMap(admins -> {
                    if (admins.isEmpty()) { log.info("No active ADMIN found, skip Lark notification"); return Mono.empty(); }
                    List<String> mobiles = admins.stream().map(Member::getMobiles).filter(m -> m != null && !m.isBlank()).distinct().toList();
                    Mono<List<String>> mobilesMono = machine.getResponsiblePersonId() == null
                            ? Mono.just(mobiles)
                            : template.selectOne(Query.query(Criteria.where("id").is(machine.getResponsiblePersonId())), Member.class)
                            .map(resp -> {
                                List<String> all = new ArrayList<>(mobiles);
                                if (resp.getMobiles() != null && !resp.getMobiles().isBlank() && !all.contains(resp.getMobiles())) all.add(resp.getMobiles());
                                return all;
                            }).defaultIfEmpty(mobiles);
                    return mobilesMono.flatMap(allMobiles -> {
                        if (allMobiles.isEmpty()) { log.warn("No mobile numbers found for notification, skip"); return Mono.empty(); }
                        return larkService.batchGetOpenIdsByMobile(allMobiles)
                                .flatMap(openIdMap -> Flux.fromIterable(openIdMap.values())
                                        .flatMap(openId -> larkService.sendMachineNotification(openId, machine)
                                                .onErrorResume(e -> { log.warn("Failed to notify openId={}: {}", openId, e.getMessage()); return Mono.empty(); }))
                                        .then());
                    });
                });
    }

    private Mono<Void> notifyMachineUpdate(Machine before, MachineDTO after, boolean personChanged, Long newPersonId) {
        List<String> changes = new ArrayList<>();
        if (!Objects.equals(before.getMachineStatus(),         after.getMachineStatus()))         changes.add("**สถานะเครื่องจักร**\\n"   + nullSafe(before.getMachineStatus())        + " → " + nullSafe(after.getMachineStatus()));
        if (!Objects.equals(before.getResponsiblePersonName(), after.getResponsiblePersonName())) changes.add("**ผู้รับผิดชอบ**\\n"      + nullSafe(before.getResponsiblePersonName()) + " → " + nullSafe(after.getResponsiblePersonName()));
        if (!Objects.equals(before.getDepartment(),            after.getDepartment()))            changes.add("**แผนก**\\n"               + nullSafe(before.getDepartment())           + " → " + nullSafe(after.getDepartment()));
        if (!Objects.equals(before.getBrand(),                 after.getBrand()))                 changes.add("**แบรนด์**\\n"             + nullSafe(before.getBrand())                + " → " + nullSafe(after.getBrand()));
        if (!Objects.equals(before.getModel(),                 after.getModel()))                 changes.add("**รุ่น**\\n"               + nullSafe(before.getModel())                + " → " + nullSafe(after.getModel()));
        if (!Objects.equals(before.getSerialNumber(),          after.getSerialNumber()))          changes.add("**หมายเลขซีเรียล**\\n"    + nullSafe(before.getSerialNumber())         + " → " + nullSafe(after.getSerialNumber()));
        if (!Objects.equals(before.getNote(),                  after.getNote()))                  changes.add("**หมายเหตุ**\\nมีการเปลี่ยนแปลง");

        if (changes.isEmpty()) { log.info("No changes detected for machine {}, skip notification", before.getMachineCode()); return Mono.empty(); }

        Mono<List<String>> adminMobilesMono = template.select(
                        Query.query(Criteria.where("role_type").is("ADMIN").and("status").is("ACTIVE")), Member.class)
                .map(Member::getMobiles).filter(m -> m != null && !m.isBlank()).distinct().collectList();

        Mono<String> newResponsibleMobileMono = (personChanged && newPersonId != null)
                ? template.selectOne(Query.query(Criteria.where("id").is(newPersonId)), Member.class)
                .map(m -> m.getMobiles() != null ? m.getMobiles() : "").defaultIfEmpty("")
                : Mono.just("");
        return Mono.zip(adminMobilesMono, newResponsibleMobileMono)
                .flatMap(tuple -> {
                    List<String> mobiles = new ArrayList<>(tuple.getT1());
                    String newRespMobile = tuple.getT2();
                    if (!newRespMobile.isBlank() && !mobiles.contains(newRespMobile)) mobiles.add(newRespMobile);
                    if (mobiles.isEmpty()) { log.info("No mobile numbers found for machine update notification, skip"); return Mono.<Void>empty(); }
                    String cardJson = buildMachineUpdateDiffCardJson(before, changes);
                    return larkService.batchGetOpenIdsByMobile(mobiles)
                            .flatMap(openIdMap -> Flux.fromIterable(openIdMap.values())
                                    .flatMap(openId -> larkService.sendCardMessage(openId, cardJson)
                                            .onErrorResume(e -> { log.warn("Failed to notify update openId={}: {}", openId, e.getMessage()); return Mono.empty(); }))
                                    .then());
                })
                .onErrorResume(e -> { log.warn("Machine update notification failed (non-blocking): {}", e.getMessage()); return Mono.empty(); });
    }

    private String buildMachineUpdateDiffCardJson(Machine before, List<String> changes) {
        StringBuilder elements = new StringBuilder();
        elements.append(String.format(
                "{\"tag\":\"div\",\"fields\":["
                        + "{\"is_short\":true,\"text\":{\"tag\":\"lark_md\",\"content\":\"**รหัสเครื่องจักร**\\n%s\"}},"
                        + "{\"is_short\":true,\"text\":{\"tag\":\"lark_md\",\"content\":\"**ชื่อเครื่องจักร**\\n%s\"}}"
                        + "]},"
                        + "{\"tag\":\"hr\"},"
                        + "{\"tag\":\"div\",\"text\":{\"tag\":\"lark_md\",\"content\":\"**รายการที่เปลี่ยนแปลง**\"}},",
                nullSafe(before.getMachineCode()), nullSafe(before.getMachineName())));
        for (int i = 0; i < changes.size(); i++) {
            elements.append(String.format(
                    "{\"tag\":\"div\",\"text\":{\"tag\":\"lark_md\",\"content\":\"%s\"}}", changes.get(i)));
            if (i < changes.size() - 1) elements.append(",");
        }
        return String.format("{"
                + "\"config\":{\"wide_screen_mode\":true},"
                + "\"header\":{\"title\":{\"tag\":\"plain_text\",\"content\":\"อัปเดตข้อมูลเครื่องจักร\"},\"template\":\"orange\"},"
                + "\"elements\":[%s]}", elements);
    }

    private Mono<Void> postDeleteTask(List<String> names, Long memberId, Long departmentId) {
        return Mono.empty();
    }

    private Long getLongValue(io.r2dbc.spi.Row row, String columnName) {
        Object v = row.get(columnName);
        return switch (v) {
            case Long l        -> l;
            case Number n      -> n.longValue();
            case null, default -> 0L;
        };
    }
}