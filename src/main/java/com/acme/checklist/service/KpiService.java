package com.acme.checklist.service;

import com.acme.checklist.entity.ChecklistRecord;
import com.acme.checklist.entity.Kpi;
import com.acme.checklist.entity.Machine;
import com.acme.checklist.entity.Member;
import com.acme.checklist.entity.ResponsibleHistory;
import com.acme.checklist.entity.enums.MachineStatus;
import com.acme.checklist.exception.ThrowException;
import com.acme.checklist.payload.ApiResponse;
import com.acme.checklist.payload.MemberPrincipal;
import com.acme.checklist.payload.PagedResponse;
import com.acme.checklist.payload.checklist.ChecklistListDTO;
import com.acme.checklist.payload.kpi.KpiDTO;
import com.acme.checklist.payload.kpi.KpiResponseDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.data.relational.core.query.Criteria;
import org.springframework.data.relational.core.query.Query;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Objects;

// ─── KpiService.java ──────────────────────────────────────────────────────────
//
//  recalculateKpiForPerson(memberId)
//  ──────────────────────────────────
//  เดิม: recalculate แค่ check_all
//  ใหม่: recalculate ทั้ง check_all + checked ในคราวเดียวกัน
//
//  เหตุผล: เมื่อ responsible person submit checklist (recheck=true) ใน ChecklistService
//  ต้อง reflect ใน checked ของ KPI ทันที ไม่รอ scheduler รายวัน 00:05
//
// ─────────────────────────────────────────────────────────────────────────────

@Slf4j
@Service
@RequiredArgsConstructor
public class KpiService {

    private final R2dbcEntityTemplate template;
    private final CommonService commonService;

    private static final ZoneId BKK = ZoneId.of("Asia/Bangkok");

    // =========================================================================
    //  CREATE
    // =========================================================================

    @Transactional
    public Mono<ApiResponse<Void>> create(KpiDTO dto) {
        return validateData(dto)
                .flatMap(validateDTO -> {
                    Kpi kpi = buildFromDTO(validateDTO);
                    return commonService.save(kpi, Kpi.class)
                            .then(Mono.just(ApiResponse.success("KP001")));
                })
                .onErrorResume(e -> {
                    log.error("[KPI] Failed to create: {}", e.getMessage());
                    return Mono.just(ApiResponse.error("KP002", e.getMessage()));
                });
    }

    // =========================================================================
    //  RECALCULATE KPI FOR PERSON  (trigger ทันทีหลัง submit checklist)
    // =========================================================================

    /**
     * Recalculate ทั้ง check_all และ checked สำหรับ member คนเดียว
     *
     * check_all  = จำนวนครั้งที่ควร submit ในเดือนนี้ (คำนวณจาก responsible_history + friday count)
     * checked    = จำนวนครั้งที่ submit จริง (นับจาก checklist_record recheck=true)
     *
     * เรียกจาก ChecklistService.saveAsResponsiblePending() ทันทีหลัง save record สำเร็จ
     */
    public Mono<Void> recalculateKpiForPerson(Long memberId) {
        if (memberId == null) return Mono.empty();

        LocalDate today       = LocalDate.now(BKK);
        YearMonth ym          = YearMonth.from(today);
        String    year        = String.valueOf(today.getYear());
        String    month       = String.format("%02d", today.getMonthValue());
        LocalDate firstDay    = ym.atDay(1);
        LocalDate lastDay     = ym.atEndOfMonth();
        LocalDate firstFriday = getFirstFridayOfMonth(ym);
        LocalDate lastFriday  = getLastFridayOfMonth(ym);
        LocalDate kpiStart    = firstFriday.with(DayOfWeek.MONDAY);
        // checked นับถึงวันนี้ แต่ไม่เกิน lastFriday ของเดือน
        LocalDate checkedEnd  = today.isAfter(lastFriday) ? lastFriday : today;

        log.info("[KPI] recalculateKpiForPerson memberId={} year={} month={} kpiStart={} checkedEnd={}",
                memberId, year, month, kpiStart, checkedEnd);

        // ── 1. คำนวณ check_all ────────────────────────────────────────────────
        Criteria historyCriteria = Criteria
                .where("responsible_person_id").is(memberId)
                .and("effective_from").lessThanOrEquals(lastDay)
                .and(Criteria.where("effective_to").isNull()
                        .or(Criteria.where("effective_to").greaterThanOrEquals(firstDay)));

        Mono<Long> checkAllMono = template.select(
                        Query.query(historyCriteria), ResponsibleHistory.class)
                .flatMap(h -> findActiveMachine(h.getMachineCode())
                        .map(machine -> {
                            LocalDate cs = clampStart(h.getEffectiveFrom(), kpiStart);
                            LocalDate ce = clampEnd(h.getEffectiveTo(), lastFriday);
                            long contribution = "MONTHLY".equals(machine.getResetPeriod())
                                    ? 1L
                                    : countFridaysInRange(cs, ce);
                            log.debug("[KPI] checkAll machine={} contribution={}", h.getMachineCode(), contribution);
                            return contribution;
                        })
                        .defaultIfEmpty(0L))
                .reduce(0L, Long::sum)
                .doOnSuccess(v -> log.info("[KPI] checkAll for memberId={} → {}", memberId, v));

        // ── 2. นับ checked จาก checklist_record ───────────────────────────────
        //       SQL เดียวกับ KpiScheduler.recalculateCurrentMonthKpi()
        //       แต่ใช้ BKK timezone และกรอง auto record ออก
        Mono<Long> checkedMono = template.getDatabaseClient()
                .sql("""
                    SELECT COUNT(*) FROM checklist_record cr
                    JOIN machine m ON cr.machine_code = m.machine_code
                    JOIN responsible_history rh
                        ON rh.machine_code = cr.machine_code
                        AND rh.responsible_person_id = $1
                        AND DATE(cr.created_at AT TIME ZONE 'Asia/Bangkok') >= rh.effective_from
                        AND (rh.effective_to IS NULL
                             OR DATE(cr.created_at AT TIME ZONE 'Asia/Bangkok') <= rh.effective_to)
                    WHERE cr.created_by = $2
                      AND cr.recheck = true
                      AND cr.check_type = 'GENERAL'
                      AND m.machine_status IN (%s)
                      AND cr.created_at >= $3
                      AND cr.created_at <= $4
                      AND (
                          cr.machine_note IS NULL
                          OR cr.machine_note != 'Automatic recording'
                          OR cr.reason_not_checked IS NULL
                          OR UPPER(cr.reason_not_checked)
                             NOT IN ('NO ACTION TAKEN', 'RESPONSIBLE PERSON DID NOT PERFORM')
                      )
                    """.formatted(MachineStatus.sqlInClause()))
                .bind(0, memberId)
                .bind(1, memberId)
                .bind(2, kpiStart.atStartOfDay(BKK).toInstant())
                .bind(3, checkedEnd.atTime(23, 59, 59).atZone(BKK).toInstant())
                .map((row, meta) -> row.get(0, Long.class))
                .one()
                .defaultIfEmpty(0L)
                .doOnSuccess(v -> log.info("[KPI] checked for memberId={} → {}", memberId, v));

        // ── 3. ดึง member ─────────────────────────────────────────────────────
        Mono<Member> memberMono = template.selectOne(
                Query.query(Criteria.where("id").is(memberId)), Member.class);

        // ── 4. zip แล้ว upsert KPI row ────────────────────────────────────────
        return Mono.zip(checkAllMono, checkedMono, memberMono)
                .flatMap(tuple -> {
                    long   newCheckAll = tuple.getT1();
                    long   newChecked  = tuple.getT2();
                    Member member      = tuple.getT3();

                    log.info("[KPI] upsert memberId={} checkAll={} checked={}", memberId, newCheckAll, newChecked);

                    return template.selectOne(
                                    Query.query(Criteria.where("member_id").is(memberId)
                                            .and("years").is(year)
                                            .and("months").is(month)),
                                    Kpi.class)
                            .flatMap(kpi -> {
                                // อัปเดต row ที่มีอยู่แล้ว
                                kpi.setCheckAll(newCheckAll);
                                kpi.setChecked(newChecked);
                                kpi.setManagerId(member.getManager());
                                kpi.setSupervisorId(member.getSupervisor());
                                return template.update(kpi).then();
                            })
                            .switchIfEmpty(Mono.defer(() -> {
                                // ยังไม่มี row → insert ใหม่
                                // (ปกติ scheduler วันที่ 1 จะสร้างให้แล้ว แต่ป้องกันกรณี edge case)
                                if (newCheckAll == 0) {
                                    log.warn("[KPI] Skip insert for memberId={} — checkAll=0", memberId);
                                    return Mono.empty();
                                }
                                Kpi newKpi = Kpi.builder()
                                        .memberId(memberId)
                                        .employeeName(member.getFirstName() + " " + member.getLastName())
                                        .years(year)
                                        .months(month)
                                        .checkAll(newCheckAll)
                                        .checked(newChecked)
                                        .managerId(member.getManager())
                                        .supervisorId(member.getSupervisor())
                                        .build();
                                return template.insert(newKpi).then();
                            }));
                })
                .doOnSuccess(v -> log.info("[KPI] recalculateKpiForPerson done memberId={}", memberId))
                .doOnError(e -> log.error("[KPI] recalculateKpiForPerson failed memberId={}: {} - {}",
                        memberId, e.getClass().getSimpleName(), e.getMessage(), e))
                .onErrorResume(e -> Mono.empty());
    }

    // =========================================================================
    //  GET LIST
    // =========================================================================

    public Mono<PagedResponse<KpiResponseDTO>> getKpiByYearAndMonth(
            String year, String month, String keyword, int index, int size) {

        return ReactiveSecurityContextHolder.getContext()
                .mapNotNull(ctx -> (MemberPrincipal) Objects.requireNonNull(ctx.getAuthentication()).getPrincipal())
                .flatMap(principal -> {
                    String role     = principal.role();
                    Long   memberId = principal.memberId();

                    Criteria base = Criteria
                            .where("years").is(year)
                            .and("months").is(month);

                    if (StringUtils.hasText(keyword)) {
                        base = base.and("employee_name").like("%" + keyword + "%").ignoreCase(true);
                    }

                    Criteria criteria = switch (role) {
                        case "MEMBER" ->
                                base.and("member_id").is(memberId);
                        case "SUPERVISOR" ->
                                base.and(
                                        Criteria.where("member_id").is(memberId)
                                                .or("supervisor_id").is(memberId));
                        case "MANAGER" ->
                                base.and(
                                        Criteria.where("member_id").is(memberId)
                                                .or("manager_id").is(memberId));
                        default -> base;
                    };

                    Query query = Query.query(criteria)
                            .with(commonService.pageable(index, size, "employee_name"));

                    return commonService.executePagedQuery(
                            index, size, query, criteria,
                            Kpi.class,
                            records -> Flux.fromIterable(records).map(KpiResponseDTO::from));
                })
                .doOnError(e -> log.error("[KPI] Failed to fetch list: {}", e.getMessage()));
    }

    // =========================================================================
    //  GET BY ID
    // =========================================================================

    public Mono<ApiResponse<KpiResponseDTO>> getById(Long id) {
        return template.selectOne(
                        Query.query(Criteria.where("id").is(id)), Kpi.class)
                .switchIfEmpty(Mono.error(new ThrowException("KP008")))
                .flatMap(kpi -> {
                    YearMonth ym = YearMonth.of(
                            Integer.parseInt(kpi.getYears()),
                            Integer.parseInt(kpi.getMonths()));

                    LocalDate firstFriday = getFirstFridayOfMonth(ym);
                    LocalDate lastFriday  = getLastFridayOfMonth(ym);
                    LocalDate start       = firstFriday.with(DayOfWeek.MONDAY);

                    // ใช้ BKK timezone ให้ตรงกับ timezone ที่ระบบใช้ทั่วไป
                    Instant startInstant = start.atStartOfDay(BKK).toInstant();
                    Instant endInstant   = lastFriday.atTime(23, 59, 59).atZone(BKK).toInstant();

                    Criteria criteria = Criteria
                            .where("created_by").is(kpi.getMemberId())
                            .and("recheck").is(true)
                            .and("check_type").is("GENERAL")
                            .and("created_at").greaterThanOrEquals(startInstant)
                            .and("created_at").lessThanOrEquals(endInstant);

                    return template.select(
                                    Query.query(criteria).sort(Sort.by("created_at").ascending()),
                                    ChecklistRecord.class)
                            .map(ChecklistListDTO::from)
                            .collectList()
                            .map(checklists -> ApiResponse.success("KP009",
                                    KpiResponseDTO.from(kpi, checklists)));
                })
                .onErrorResume(e -> {
                    log.error("[KPI] Failed to fetch by id: {}", e.getMessage(), e);
                    return Mono.just(ApiResponse.error("KP010", e.getMessage()));
                });
    }

    // =========================================================================
    //  VALIDATE
    // =========================================================================

    public Mono<KpiDTO> validateData(KpiDTO kpiDTO) {
        if (kpiDTO.getMemberId() == null)
            return Mono.error(new ThrowException("KP003"));
        if (kpiDTO.getEmployeeName() == null || kpiDTO.getEmployeeName().isEmpty())
            return Mono.error(new ThrowException("KP004"));
        if (kpiDTO.getYears() == null || kpiDTO.getYears().isEmpty())
            return Mono.error(new ThrowException("KP005"));
        if (kpiDTO.getMonths() == null || kpiDTO.getMonths().isEmpty())
            return Mono.error(new ThrowException("KP006"));
        if (kpiDTO.getCheckAll() == null)
            return Mono.error(new ThrowException("KP007"));
        return Mono.just(kpiDTO);
    }

    // =========================================================================
    //  BUILD
    // =========================================================================

    public Kpi buildFromDTO(KpiDTO kpiDTO) {
        return Kpi.builder()
                .id(kpiDTO.getId())
                .memberId(kpiDTO.getMemberId())
                .employeeName(kpiDTO.getEmployeeName())
                .years(kpiDTO.getYears())
                .months(kpiDTO.getMonths())
                .checkAll(kpiDTO.getCheckAll())
                .checked(kpiDTO.getChecked())
                .managerId(kpiDTO.getManagerId())
                .supervisorId(kpiDTO.getSupervisorId())
                .build();
    }

    // =========================================================================
    //  HELPERS
    // =========================================================================

    private Mono<Machine> findActiveMachine(String machineCode) {
        return template.select(
                        Query.query(Criteria.where("machine_code").is(machineCode)
                                .and("machine_status").in(MachineStatus.activeDbValues())),
                        Machine.class)
                .next();
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

    private LocalDate getFirstFridayOfMonth(YearMonth ym) {
        LocalDate d = ym.atDay(1);
        while (d.getDayOfWeek() != DayOfWeek.FRIDAY) d = d.plusDays(1);
        return d;
    }

    private LocalDate getLastFridayOfMonth(YearMonth ym) {
        LocalDate d = ym.atEndOfMonth();
        while (d.getDayOfWeek() != DayOfWeek.FRIDAY) d = d.minusDays(1);
        return d;
    }
}