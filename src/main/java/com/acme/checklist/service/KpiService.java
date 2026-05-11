package com.acme.checklist.service;

import com.acme.checklist.entity.ChecklistRecord;
import com.acme.checklist.entity.Kpi;
import com.acme.checklist.entity.Machine;
import com.acme.checklist.entity.Member;
import com.acme.checklist.entity.ResponsibleHistory;
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
import java.time.ZoneOffset;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class KpiService {

    private final R2dbcEntityTemplate template;
    private final CommonService commonService;

    private static final List<String> ACTIVE_STATUSES = List.of("IN USE", "NOT IN USE", "UNDER MAINTENANCE");

    // ─── CREATE ───────────────────────────────────────────────────────────────

    @Transactional
    public Mono<ApiResponse<Void>> create(KpiDTO dto) {
        return validateData(dto)
                .flatMap(validateDTO -> {
                    Kpi kpi = buildFromDTO(validateDTO);
                    return commonService.save(kpi, Kpi.class)
                            .then(Mono.just(ApiResponse.success("KP001")));
                })
                .onErrorResume(e -> {
                    log.error("Failed to create the kpi: {}", e.getMessage());
                    return Mono.just(ApiResponse.error("KP002", e.getMessage()));
                });
    }

    // ─── RECALCULATE KPI FOR PERSON ───────────────────────────────────────────
    // ใช้แทน updateOrCreateKpi() — คำนวณจาก responsible_history จริง
    // เรียกจาก ChecklistService และ MachineService

    public Mono<Void> recalculateKpiForPerson(Long memberId) {
        if (memberId == null) return Mono.empty();

        LocalDate today      = LocalDate.now();
        YearMonth ym         = YearMonth.from(today);
        String year          = String.valueOf(today.getYear());
        String month         = String.format("%02d", today.getMonthValue());
        LocalDate firstDay   = ym.atDay(1);
        LocalDate lastDay    = ym.atEndOfMonth();
        LocalDate lastFriday = getLastFridayOfMonth(ym);

        // ดึง history ของ member ที่ active ในเดือนนี้
        Criteria historyCriteria = Criteria
                .where("responsible_person_id").is(memberId)
                .and("effective_from").lessThanOrEquals(lastDay)
                .and(Criteria.where("effective_to").isNull()
                        .or(Criteria.where("effective_to").greaterThanOrEquals(firstDay)));

        // นับวันศุกร์จาก history จริง (คำนึง partial month + machine active)
        Mono<Long> checkAllMono = template.select(
                        Query.query(historyCriteria), ResponsibleHistory.class)
                .filterWhen(h -> isMachineActive(h.getMachineCode()))
                .collectList()
                .map(histories -> histories.stream()
                        .mapToLong(h -> countFridaysInRange(
                                clampStart(h.getEffectiveFrom(), firstDay),
                                clampEnd(h.getEffectiveTo(), lastFriday)))
                        .sum());

        Mono<Member> memberMono = template.selectOne(
                Query.query(Criteria.where("id").is(memberId)), Member.class);

        return Mono.zip(checkAllMono, memberMono)
                .flatMap(tuple -> {
                    long   newCheckAll = tuple.getT1();
                    Member member      = tuple.getT2();

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

    // ─── GET LIST ─────────────────────────────────────────────────────────────

    public Mono<PagedResponse<KpiResponseDTO>> getKpiByYearAndMonth(
            String year, String month, String keyword, int index, int size) {

        return ReactiveSecurityContextHolder.getContext()
                .map(ctx -> (MemberPrincipal) ctx.getAuthentication().getPrincipal())
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
                .doOnError(e -> log.error("Failed to fetch KPI: {}", e.getMessage()));
    }

    // ─── GET BY ID ────────────────────────────────────────────────────────────

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
                    LocalDate end         = lastFriday;

                    Instant startInstant = start.atStartOfDay(ZoneOffset.UTC).toInstant();
                    Instant endInstant   = end.atTime(23, 59, 59).atZone(ZoneOffset.UTC).toInstant();

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
                    log.error("Failed to fetch KPI by id: {}", e.getMessage(), e);
                    return Mono.just(ApiResponse.error("KP010", e.getMessage()));
                });
    }

    // ─── VALIDATE ─────────────────────────────────────────────────────────────

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

    // ─── BUILD ────────────────────────────────────────────────────────────────

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