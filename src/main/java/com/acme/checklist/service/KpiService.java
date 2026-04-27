package com.acme.checklist.service;

import com.acme.checklist.entity.ChecklistRecord;
import com.acme.checklist.entity.Kpi;
import com.acme.checklist.entity.Machine;
import com.acme.checklist.entity.Member;
import com.acme.checklist.exception.ThrowException;
import com.acme.checklist.payload.ApiResponse;
import com.acme.checklist.payload.kpi.KpiDTO;
import com.acme.checklist.payload.kpi.KpiResponseDTO;
import com.acme.checklist.payload.checklist.ChecklistListDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.data.relational.core.query.Criteria;
import org.springframework.data.relational.core.query.Query;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;
import com.acme.checklist.payload.MemberPrincipal;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;

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

    // ─── UPDATE OR CREATE ─────────────────────────────────────────────────────

    public Mono<Void> updateOrCreateKpi(String responsiblePersonId, String year, String month) {
        if (responsiblePersonId == null || responsiblePersonId.trim().isEmpty()) {
            return Mono.error(new IllegalArgumentException("ResponsiblePersonId cannot be null"));
        }

        YearMonth yearMonth = YearMonth.of(Integer.parseInt(year), Integer.parseInt(month));
        int fridays = countFridaysInMonth(yearMonth);

        Mono<Long> machineCountMono = template.count(
                Query.query(Criteria.where("responsible_person_id").is(responsiblePersonId)
                        .and("machine_status").not("ยกเลิกใช้งาน")),
                Machine.class
        );

        Mono<Machine> machineMono = template.select(
                Query.query(Criteria.where("responsible_person_id").is(responsiblePersonId)
                        .and("machine_status").not("ยกเลิกใช้งาน")),
                Machine.class
        ).next();

        Mono<Member> memberMono = template.selectOne(
                Query.query(Criteria.where("employee_id").is(responsiblePersonId)),
                Member.class
        );

        Mono<Kpi> existingKpiMono = template.selectOne(
                Query.query(Criteria.where("employee_id").is(responsiblePersonId)
                        .and("years").is(year)
                        .and("months").is(month)),
                Kpi.class
        );

        return Mono.zip(machineCountMono, machineMono, memberMono, existingKpiMono.defaultIfEmpty(new Kpi()))
                .flatMap(tuple -> {
                    long machineCount = tuple.getT1();
                    Machine machine   = tuple.getT2();
                    Member member     = tuple.getT3();
                    Kpi existingKpi   = tuple.getT4();

                    if (machineCount == 0) {
                        log.info("No active machines for: {}", responsiblePersonId);
                        return Mono.empty();
                    }

                    String employeeName = member.getFirstName() + " " + member.getLastName();

                    if (existingKpi.getId() != null) {
                        existingKpi.setCheckAll((long) fridays * machineCount);
                        existingKpi.setChecked(existingKpi.getChecked() + 1);
                        existingKpi.setEmployeeName(employeeName);
                        existingKpi.setManagerId(machine.getManagerId());
                        existingKpi.setSupervisorId(machine.getSupervisorId());
                        return template.update(existingKpi).then();
                    } else {
                        Kpi newKpi = Kpi.builder()
                                .employeeId(responsiblePersonId)
                                .employeeName(employeeName)
                                .years(year)
                                .months(month)
                                .checkAll((long) fridays * machineCount)
                                .checked(1L)
                                .managerId(machine.getManagerId())
                                .supervisorId(machine.getSupervisorId())
                                .build();
                        return template.insert(newKpi).then();
                    }
                })
                .onErrorResume(e -> {
                    log.error("Failed to update KPI: {}", e.getMessage(), e);
                    return Mono.empty();
                });
    }

    // ─── GET LIST ─────────────────────────────────────────────────────────────

    public Mono<List<Kpi>> getKpiByYearAndMonth(String year, String month) {
        return ReactiveSecurityContextHolder.getContext()
                .map(ctx -> (MemberPrincipal) ctx.getAuthentication().getPrincipal())
                .flatMap(principal -> {
                    String employeeId = principal.employeeId();
                    String role       = principal.role();

                    Criteria base = Criteria
                            .where("years").is(year)
                            .and("months").is(month);

                    Criteria criteria = switch (role) {
                        case "MEMBER" ->
                                base.and("employee_id").is(employeeId);
                        case "SUPERVISOR" ->
                                base.and(
                                        Criteria.where("employee_id").is(employeeId)
                                                .or("supervisor_id").is(employeeId)
                                );
                        case "MANAGER" ->
                                base.and(
                                        Criteria.where("employee_id").is(employeeId)
                                                .or("manager_id").is(employeeId)
                                );
                        default -> base;
                    };

                    return template.select(Query.query(criteria), Kpi.class)
                            .distinct(Kpi::getEmployeeId)
                            .collectList();
                })
                .doOnError(e -> log.error("Failed to fetch KPI: {}", e.getMessage()));
    }

    // ─── GET BY ID ────────────────────────────────────────────────────────────

    public Mono<ApiResponse<KpiResponseDTO>> getById(Long id) {
        return template.selectOne(
                        Query.query(Criteria.where("id").is(id)),
                        Kpi.class
                )
                .switchIfEmpty(Mono.error(new ThrowException("KP008")))
                .flatMap(kpi -> {
                    YearMonth ym = YearMonth.of(
                            Integer.parseInt(kpi.getYears()),
                            Integer.parseInt(kpi.getMonths())
                    );

                    // start = จันทร์ของสัปดาห์ที่มีศุกร์แรกของเดือน
                    // end   = ศุกร์สุดท้ายของเดือน
                    LocalDate firstFriday = getFirstFridayOfMonth(ym);
                    LocalDate lastFriday  = getLastFridayOfMonth(ym);
                    LocalDate start       = firstFriday.with(DayOfWeek.MONDAY);
                    LocalDate end         = lastFriday;

                    Instant startInstant = start.atStartOfDay(ZoneOffset.UTC).toInstant();
                    Instant endInstant   = end.atTime(23, 59, 59).atZone(ZoneOffset.UTC).toInstant();

                    Criteria criteria = Criteria
                            .where("user_id").is(kpi.getEmployeeId())
                            .and("recheck").is(true)
                            .and("check_type").is("GENERAL")
                            .and("created_at").greaterThanOrEquals(startInstant)
                            .and("created_at").lessThanOrEquals(endInstant);

                    return template.select(
                                    Query.query(criteria).sort(Sort.by("created_at").ascending()),
                                    ChecklistRecord.class
                            )
                            .map(ChecklistListDTO::from)
                            .collectList()
                            .map(checklists -> ApiResponse.success("KP009", KpiResponseDTO.from(kpi, checklists)));
                })
                .onErrorResume(e -> {
                    log.error("Failed to fetch KPI by id: {}", e.getMessage(), e);
                    return Mono.just(ApiResponse.error("KP010", e.getMessage()));
                });
    }

    // ─── VALIDATE ─────────────────────────────────────────────────────────────

    public Mono<KpiDTO> validateData(KpiDTO kpiDTO) {
        if (kpiDTO.getEmployeeId() == null || kpiDTO.getEmployeeId().isEmpty())
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
                .employeeId(kpiDTO.getEmployeeId())
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

    private int countFridaysInMonth(YearMonth yearMonth) {
        LocalDate firstDay = yearMonth.atDay(1);
        LocalDate lastDay  = yearMonth.atEndOfMonth();
        int fridays = 0;
        LocalDate date = firstDay;
        while (!date.isAfter(lastDay)) {
            if (date.getDayOfWeek().getValue() == 5) fridays++;
            date = date.plusDays(1);
        }
        return fridays;
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