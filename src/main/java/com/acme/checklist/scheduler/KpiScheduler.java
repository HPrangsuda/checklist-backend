package com.acme.checklist.scheduler;

import com.acme.checklist.entity.*;
import com.acme.checklist.entity.ResponsibleHistory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.data.relational.core.query.Criteria;
import org.springframework.data.relational.core.query.Query;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;

@Slf4j
@Component
@RequiredArgsConstructor
public class KpiScheduler {

    private final R2dbcEntityTemplate template;

    // ─── 1. สร้าง KPI ต้นเดือน ─────────────────────────────────────────────────
    @Scheduled(cron = "0 30 0 1 * ?")
    public void createKpiRecords() {
        LocalDate today = LocalDate.now();
        String year  = String.valueOf(today.getYear());
        String month = String.format("%02d", today.getMonthValue());
        YearMonth ym = YearMonth.from(today);

        LocalDate firstDay = ym.atDay(1);
        LocalDate lastDay  = ym.atEndOfMonth();

        log.info("=== Creating KPI records for {}-{} ===", year, month);

        Criteria historyCriteria = Criteria
                .where("effective_from").lessThanOrEquals(lastDay)
                .and(Criteria.where("effective_to").isNull()
                        .or(Criteria.where("effective_to").greaterThanOrEquals(firstDay)));

        template.select(Query.query(historyCriteria), ResponsibleHistory.class)
                .filterWhen(h -> isMachineActive(h.getMachineCode()))
                .collectMultimap(ResponsibleHistory::getResponsiblePersonId)
                .flatMap(historyMap -> Flux.fromIterable(historyMap.entrySet())
                        .flatMap(entry -> {
                            String personId = entry.getKey();
                            long checkAll = entry.getValue().stream()
                                    .mapToLong(h -> countFridaysInRange(
                                            clampStart(h.getEffectiveFrom(), firstDay),
                                            clampEnd(h.getEffectiveTo(), lastDay)))
                                    .sum();

                            return template.selectOne(
                                            Query.query(Criteria.where("employee_id").is(personId)),
                                            Member.class)
                                    .flatMap(member -> template.select(
                                                    Query.query(Criteria.where("responsible_person_id").is(personId)
                                                            .and("machine_status").not("CANCELLED")),
                                                    Machine.class)
                                            .next()
                                            .flatMap(machine -> {
                                                Kpi kpi = Kpi.builder()
                                                        .employeeId(personId)
                                                        .employeeName(member.getFirstName() + " " + member.getLastName())
                                                        .years(year)
                                                        .months(month)
                                                        .checkAll(checkAll)
                                                        .checked(0L)
                                                        .managerId(machine.getManagerId())
                                                        .supervisorId(machine.getSupervisorId())
                                                        .build();
                                                return template.insert(kpi).then();
                                            }))
                                    .onErrorResume(e -> {
                                        log.error("Failed to create KPI for {}: {}", personId, e.getMessage());
                                        return Mono.empty();
                                    });
                        }).then()
                )
                .subscribe(null, e -> log.error("createKpiRecords failed: {}", e.getMessage()));
    }

    // ─── 2. Recalculate รายวัน ──────────────────────────────────────────────────
    @Scheduled(cron = "0 35 0 * * *")
    public void recalculateCurrentMonthKpi() {
        LocalDate today = LocalDate.now();
        String year  = String.valueOf(today.getYear());
        String month = String.format("%02d", today.getMonthValue());
        YearMonth ym = YearMonth.from(today);

        LocalDate firstDay    = ym.atDay(1);
        LocalDate lastDay     = ym.atEndOfMonth();
        LocalDate firstFriday = getFirstFridayOfMonth(ym);
        LocalDate lastFriday  = getLastFridayOfMonth(ym);
        LocalDate start       = firstFriday.with(DayOfWeek.MONDAY);
        LocalDate endDate     = today.isBefore(lastFriday) ? today : lastFriday;

        log.info("=== Recalculating KPI for {}-{} ===", year, month);

        Criteria historyCriteria = Criteria
                .where("effective_from").lessThanOrEquals(lastDay)
                .and(Criteria.where("effective_to").isNull()
                        .or(Criteria.where("effective_to").greaterThanOrEquals(firstDay)));

        template.select(
                        Query.query(Criteria.where("years").is(year).and("months").is(month)),
                        Kpi.class)
                .flatMap(kpi -> {
                    String personId = kpi.getEmployeeId();

                    // ── checkAll จาก history ────────────────────────────────
                    Mono<Long> checkAllMono = template.select(
                                    Query.query(historyCriteria
                                            .and(Criteria.where("responsible_person_id").is(personId))),
                                    ResponsibleHistory.class)
                            .filterWhen(h -> isMachineActive(h.getMachineCode()))
                            .collectList()
                            .map(histories -> histories.stream()
                                    .mapToLong(h -> countFridaysInRange(
                                            clampStart(h.getEffectiveFrom(), firstDay),
                                            clampEnd(h.getEffectiveTo(), lastDay)))
                                    .sum());

                    // ── checked count ────────────────────────────────────────
                    Mono<Long> checkedMono = template.count(
                            Query.query(Criteria.where("user_id").is(personId)
                                    .and("recheck").is(true)
                                    .and("check_type").is("GENERAL")
                                    .and("created_at").greaterThanOrEquals(
                                            start.atStartOfDay().toInstant(ZoneOffset.UTC))
                                    .and("created_at").lessThanOrEquals(
                                            endDate.atTime(23, 59, 59).toInstant(ZoneOffset.UTC))),
                            ChecklistRecord.class);

                    // ── machine ปัจจุบัน (manager/supervisor) ───────────────
                    Mono<Machine> machineMono = template.select(
                                    Query.query(Criteria.where("responsible_person_id").is(personId)
                                            .and("machine_status").not("CANCELLED")),
                                    Machine.class)
                            .next();

                    return Mono.zip(checkAllMono, checkedMono, machineMono)
                            .flatMap(tuple -> {
                                long newCheckAll  = tuple.getT1();
                                long checkedCount = tuple.getT2();
                                Machine machine   = tuple.getT3();

                                if (newCheckAll == 0) return Mono.empty();

                                kpi.setCheckAll(newCheckAll);
                                kpi.setChecked(checkedCount);
                                kpi.setManagerId(machine.getManagerId());
                                kpi.setSupervisorId(machine.getSupervisorId());

                                return template.update(kpi)
                                        .doOnSuccess(v -> log.info("Updated KPI {} → checkAll={}, checked={}",
                                                personId, newCheckAll, checkedCount))
                                        .then();
                            })
                            .onErrorResume(e -> {
                                log.error("Failed to recalculate KPI for {}: {}", personId, e.getMessage());
                                return Mono.empty();
                            });
                })
                .then()
                .subscribe(null, e -> log.error("recalculateCurrentMonthKpi failed: {}", e.getMessage()));
    }

    // ─── HELPERS ───────────────────────────────────────────────────────────────

    private Mono<Boolean> isMachineActive(String machineId) {
        return template.selectOne(
                        Query.query(Criteria.where("id").is(machineId)),
                        Machine.class)
                .map(m -> !"CANCELLED".equals(m.getMachineStatus())
                        && !"MONTHLY".equals(m.getResetPeriod()))
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

    private LocalDate clampStart(LocalDate effectiveFrom, LocalDate monthStart) {
        return effectiveFrom.isBefore(monthStart) ? monthStart : effectiveFrom;
    }

    private LocalDate clampEnd(LocalDate effectiveTo, LocalDate monthEnd) {
        return (effectiveTo == null || effectiveTo.isAfter(monthEnd)) ? monthEnd : effectiveTo;
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