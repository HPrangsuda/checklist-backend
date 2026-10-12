package com.acme.checklist.scheduler;

import com.acme.checklist.entity.Machine;
import com.acme.checklist.entity.Member;
import com.acme.checklist.entity.ResponsibleHistory;
import com.acme.checklist.entity.enums.MachineStatus;
import com.acme.checklist.service.KpiService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.data.relational.core.query.Criteria;
import org.springframework.data.relational.core.query.Query;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicInteger;

import static com.acme.checklist.scheduler.SchedulerLocks.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class KpiScheduler {

    private final R2dbcEntityTemplate template;
    private final KpiService kpiService;

    private static final ZoneId BKK = ZoneId.of("Asia/Bangkok");

    /** จำนวน query เครื่องจักรพร้อมกันสูงสุด (กัน connection pool เต็ม) */
    private static final int DB_CONCURRENCY = 8;

    // =========================================================================
    //  1. สร้าง KPI ต้นเดือน (วันที่ 1 00:00)
    //     check_all = (เครื่อง non-MONTHLY × จำนวน Friday) + (เครื่อง MONTHLY × 1)
    // =========================================================================

    @Scheduled(cron = "0 0 0 1 * ?", zone = "Asia/Bangkok")
    @SchedulerLock(name = KPI_CREATE_MONTHLY,
            lockAtLeastFor = ASYNC_LOCK_AT_LEAST, lockAtMostFor = ASYNC_LOCK_AT_MOST)
    public void createKpiRecords() {
        long startedAt = System.currentTimeMillis();

        LocalDate today       = LocalDate.now(BKK);
        String    year        = String.valueOf(today.getYear());
        String    month       = String.format("%02d", today.getMonthValue());
        YearMonth ym          = YearMonth.from(today);
        LocalDate firstFriday = getFirstFridayOfMonth(ym);
        LocalDate lastFriday  = getLastFridayOfMonth(ym);
        LocalDate kpiStart    = firstFriday.with(DayOfWeek.MONDAY);

        log.info("[KPI][CREATE] ▶ start year={} month={} kpiStart={} kpiEnd={}", year, month, kpiStart, lastFriday);

        AtomicInteger upserted = new AtomicInteger();
        AtomicInteger skipped  = new AtomicInteger();
        AtomicInteger failed   = new AtomicInteger();

        fetchHistory(kpiStart, lastFriday)
                .flatMap(h -> findActiveMachine(h.getMachineCode())
                        .map(machine -> {
                            LocalDate cs = clampStart(h.getEffectiveFrom(), kpiStart);
                            LocalDate ce = clampEnd(h.getEffectiveTo(), lastFriday);
                            long contribution = "MONTHLY".equals(machine.getResetPeriod())
                                    ? 1L
                                    : countFridaysInRange(cs, ce);
                            log.debug("[KPI][CREATE] machine={} resetPeriod={} memberId={} contribution={}",
                                    h.getMachineCode(), machine.getResetPeriod(), h.getResponsiblePersonId(), contribution);
                            return new MemberContribution(h.getResponsiblePersonId(), contribution);
                        })
                        .doOnSuccess(mc -> {
                            if (mc == null)
                                log.debug("[KPI][CREATE] machine={} inactive — skipped", h.getMachineCode());
                        }), DB_CONCURRENCY)
                .filter(mc -> mc.memberId() != null)
                .collectMultimap(MemberContribution::memberId, MemberContribution::contribution)
                .doOnNext(map -> log.info("[KPI][CREATE] Members from history: {}", map.size()))
                .flatMap(contributionMap ->
                        Flux.fromIterable(contributionMap.entrySet())
                                .concatMap(entry -> {
                                    Long memberId = entry.getKey();
                                    long checkAll = entry.getValue().stream().mapToLong(Long::longValue).sum();

                                    return template.selectOne(
                                                    Query.query(Criteria.where("id").is(memberId)
                                                            .and("status").is("ACTIVE")),
                                                    Member.class)
                                            .switchIfEmpty(Mono.defer(() -> {
                                                skipped.incrementAndGet();
                                                log.warn("[KPI][CREATE] memberId={} not found or INACTIVE — skipped", memberId);
                                                return Mono.empty();
                                            }))
                                            .flatMap(member -> insertKpiIfAbsent(
                                                    memberId,
                                                    member.getFirstName() + " " + member.getLastName(),
                                                    year, month, checkAll,
                                                    member.getManager(),
                                                    member.getSupervisor()
                                            ).doOnSuccess(v -> upserted.incrementAndGet()))
                                            .onErrorResume(e -> {
                                                failed.incrementAndGet();
                                                log.error("[KPI][CREATE] ✗ Failed memberId={}: {} - {}",
                                                        memberId, e.getClass().getSimpleName(), e.getMessage(), e);
                                                return Mono.empty();
                                            });
                                })
                                .then()
                )
                .subscribe(
                        null,
                        e -> log.error("[KPI][CREATE] ✗ Failed after {} ms: {} - {}",
                                System.currentTimeMillis() - startedAt, e.getClass().getSimpleName(), e.getMessage(), e),
                        () -> log.info("[KPI][CREATE] ■ done in {} ms | upserted={} skipped={} failed={}",
                                System.currentTimeMillis() - startedAt, upserted.get(), skipped.get(), failed.get())
                );
    }

    // =========================================================================
    //  2. คำนวณ KPI เดือนปัจจุบันใหม่ (ทุกวัน 00:05)
    // =========================================================================

    @Scheduled(cron = "0 5 0 * * *", zone = "Asia/Bangkok")
    @SchedulerLock(name = KPI_RECALCULATE_DAILY,
            lockAtLeastFor = ASYNC_LOCK_AT_LEAST, lockAtMostFor = ASYNC_LOCK_AT_MOST)
    public void recalculateCurrentMonthKpi() {
        long startedAt = System.currentTimeMillis();

        LocalDate today = LocalDate.now(BKK);
        String    year  = String.valueOf(today.getYear());
        String    month = String.format("%02d", today.getMonthValue());

        log.info("[KPI][RECALC] ▶ start year={} month={}", year, month);

        AtomicInteger ok     = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();

        template.getDatabaseClient()
                .sql("SELECT member_id FROM kpi WHERE years = $1 AND months = $2")
                .bind(0, year)
                .bind(1, month)
                .map((row, meta) -> row.get("member_id", Long.class))
                .all()
                .collectList()   // อ่านให้ครบก่อน แล้วค่อยคำนวณทีละคน
                .flatMapMany(Flux::fromIterable)
                .concatMap(memberId ->
                        kpiService.recalculateKpiForPerson(memberId)
                                .doOnSuccess(v -> ok.incrementAndGet())
                                .onErrorResume(e -> {
                                    failed.incrementAndGet();
                                    log.error("[KPI][RECALC] ✗ Failed memberId={}: {}", memberId, e.getMessage(), e);
                                    return Mono.empty();
                                })
                )
                .then()
                .subscribe(
                        null,
                        e -> log.error("[KPI][RECALC] ✗ Failed after {} ms: {}",
                                System.currentTimeMillis() - startedAt, e.getMessage(), e),
                        () -> log.info("[KPI][RECALC] ■ done in {} ms | ok={} failed={}",
                                System.currentTimeMillis() - startedAt, ok.get(), failed.get())
                );
    }

    // =========================================================================
    //  Helpers
    // =========================================================================

    private Mono<Void> insertKpiIfAbsent(Long memberId, String employeeName,
                                         String year, String month,
                                         long checkAll,
                                         Long managerId, Long supervisorId) {
        DatabaseClient.GenericExecuteSpec spec = template.getDatabaseClient()
                .sql("""
                    INSERT INTO kpi (member_id, employee_name, years, months, check_all, checked, manager_id, supervisor_id)
                    VALUES ($1, $2, $3, $4, $5, $6, $7, $8)
                    ON CONFLICT (member_id, years, months) DO UPDATE
                      SET check_all     = EXCLUDED.check_all,
                          employee_name = EXCLUDED.employee_name,
                          manager_id    = EXCLUDED.manager_id,
                          supervisor_id = EXCLUDED.supervisor_id
                    """)
                .bind(0, memberId)
                .bind(1, employeeName)
                .bind(2, year)
                .bind(3, month)
                .bind(4, checkAll)
                .bind(5, 0L);

        spec = managerId    != null ? spec.bind(6, managerId)    : spec.bindNull(6, Long.class);
        spec = supervisorId != null ? spec.bind(7, supervisorId) : spec.bindNull(7, Long.class);

        return spec.then()
                .doOnSuccess(v -> log.info("[KPI][CREATE] ✓ Upserted memberId={} {}-{} checkAll={}",
                        memberId, year, month, checkAll));
    }

    private Flux<ResponsibleHistory> fetchHistory(LocalDate kpiStart, LocalDate kpiEnd) {
        return template.getDatabaseClient()
                .sql("""
                    SELECT machine_code, responsible_person_id, effective_from, effective_to
                    FROM responsible_history
                    WHERE effective_from <= $1
                      AND (effective_to IS NULL OR effective_to >= $2)
                    """)
                .bind(0, kpiEnd)
                .bind(1, kpiStart)
                .map((row, meta) -> ResponsibleHistory.builder()
                        .machineCode(row.get("machine_code", String.class))
                        .responsiblePersonId(row.get("responsible_person_id", Long.class))
                        .effectiveFrom(row.get("effective_from", LocalDate.class))
                        .effectiveTo(row.get("effective_to", LocalDate.class))
                        .build())
                .all()
                // อ่านให้ครบก่อน แล้วค่อย query ต่อ (ไม่ query ซ้อนระหว่างที่ยัง stream ผลอยู่)
                .collectList()
                .flatMapMany(Flux::fromIterable);
    }

    private Mono<Machine> findActiveMachine(String machineCode) {
        if (machineCode == null) return Mono.empty();
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

    private LocalDate clampStart(LocalDate effectiveFrom, LocalDate rangeStart) {
        return (effectiveFrom == null || effectiveFrom.isBefore(rangeStart)) ? rangeStart : effectiveFrom;
    }

    private LocalDate clampEnd(LocalDate effectiveTo, LocalDate rangeEnd) {
        return (effectiveTo == null || effectiveTo.isAfter(rangeEnd)) ? rangeEnd : effectiveTo;
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

    private record MemberContribution(Long memberId, Long contribution) {}
}