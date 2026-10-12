package com.acme.checklist.scheduler;

import com.acme.checklist.entity.ChecklistRecord;
import com.acme.checklist.entity.Machine;
import com.acme.checklist.entity.MachineChecklist;
import com.acme.checklist.entity.Member;
import com.acme.checklist.entity.enums.MachineStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.data.relational.core.query.Criteria;
import org.springframework.data.relational.core.query.Query;
import org.springframework.data.relational.core.query.Update;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static com.acme.checklist.scheduler.SchedulerLocks.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class ChecklistScheduler {

    private final R2dbcEntityTemplate template;
    private final ReactiveTransactionManager transactionManager;

    private static final ZoneId ZONE = ZoneId.of("Asia/Bangkok");
    private static final String WEEKLY_CRON = "0 0 0 * * 1";

    /** job ไหนค้างเกินนี้จะถูกยกเลิก — กัน scheduler thread ค้างจน job อื่นไม่ได้รัน */
    private static final Duration JOB_TIMEOUT = Duration.ofMinutes(20);

    /** จำนวน id ต่อ 1 คำสั่ง UPDATE ... WHERE id IN (...) */
    private static final int UPDATE_BATCH_SIZE = 1000;

    private static final List<String> PENDING_STATUSES = List.of("PENDING SUPERVISOR", "PENDING MANAGER");

    // ─── WEEKLY: mark overdue + reset (จันทร์ 00:05) ─────────────────────────

    @Scheduled(cron = "0 5 0 * * MON", zone = "Asia/Bangkok")
    @SchedulerLock(name = CHECKLIST_OVERDUE_WEEKLY,
            lockAtLeastFor = BLOCKING_LOCK_AT_LEAST, lockAtMostFor = BLOCKING_LOCK_AT_MOST)
    public void updateOverdueChecklistsWeek() {
        runBlocking("OVERDUE-WEEKLY", overdueThenReset("WEEKLY"));
    }

    // ─── MONTHLY: mark overdue + reset (วันที่ 1 00:10) ──────────────────────

    @Scheduled(cron = "0 10 0 1 * *", zone = "Asia/Bangkok")
    @SchedulerLock(name = CHECKLIST_OVERDUE_MONTHLY,
            lockAtLeastFor = BLOCKING_LOCK_AT_LEAST, lockAtMostFor = BLOCKING_LOCK_AT_MOST)
    public void updateOverdueChecklistsMonth() {
        runBlocking("OVERDUE-MONTHLY", overdueThenReset("MONTHLY"));
    }

    /** mark overdue และ reset อยู่ใน transaction เดียว: สำเร็จทั้งคู่หรือไม่เปลี่ยนอะไรเลย */
    private Mono<Void> overdueThenReset(String period) {
        TransactionalOperator tx = TransactionalOperator.create(transactionManager);
        return tx.transactional(
                buildUpdateOverdue(period).then(Mono.defer(() -> resetMachineCheckStatus(period))));
    }

    // ─── RESET MACHINE CHECKLIST ตาม reset_time (ทุกวัน 00:00) ───────────────

    @Scheduled(cron = "0 0 0 * * *", zone = "Asia/Bangkok")
    @SchedulerLock(name = CHECKLIST_RESET_BY_RESET_TIME,
            lockAtLeastFor = BLOCKING_LOCK_AT_LEAST, lockAtMostFor = BLOCKING_LOCK_AT_MOST)
    public void resetMachineChecklistByResetTime() {
        LocalDate today = LocalDate.now(ZONE);

        Mono<Void> work = template.select(
                        Query.query(Criteria.where("check_status").is(true)
                                .and("reset_time").not(WEEKLY_CRON)),
                        MachineChecklist.class
                )
                .filter(item -> shouldResetToday(item.getId(), item.getResetTime(), today))
                .map(MachineChecklist::getId)
                .collectList()
                .flatMap(ids -> {
                    if (ids.isEmpty()) {
                        log.info("[CHECKLIST][RESET] No checklist items to reset today");
                        return Mono.empty();
                    }
                    log.info("[CHECKLIST][RESET] Resetting {} checklist items", ids.size());

                    // UPDATE ทีละก้อนแทนทีละแถว
                    return Flux.fromIterable(ids)
                            .buffer(UPDATE_BATCH_SIZE)
                            .concatMap(batch -> template.update(MachineChecklist.class)
                                    .matching(Query.query(Criteria.where("id").in(batch)))
                                    .apply(Update.update("check_status", false)))
                            .then();
                });

        runBlocking("RESET-BY-RESET-TIME", work);
    }

    // ─── AUTO SAVE WEEKLY (ศุกร์ 15:00) ──────────────────────────────────────

    @Scheduled(cron = "0 0 15 * * FRI", zone = "Asia/Bangkok")
    @SchedulerLock(name = CHECKLIST_AUTO_SAVE_WEEKLY,
            lockAtLeastFor = BLOCKING_LOCK_AT_LEAST, lockAtMostFor = BLOCKING_LOCK_AT_MOST)
    public void autoSaveWeeklyChecklistRecords() {
        LocalDate today  = LocalDate.now(ZONE);
        LocalDate monday = today.with(DayOfWeek.MONDAY);
        LocalDate friday = today.with(DayOfWeek.FRIDAY);

        Instant from = monday.atStartOfDay(ZONE).toInstant();
        Instant to   = friday.atTime(LocalTime.MAX).atZone(ZONE).toInstant();

        runBlocking("AUTO-SAVE-WEEKLY", autoSave("WEEKLY", from, to));
    }

    // ─── AUTO SAVE MONTHLY (วันสุดท้ายของเดือน 23:55) ──────────────────────

    /*
     * เก็บตกเครื่องที่ยังไม่มี record ในเดือนนี้ ก่อนปิดรอบ
     * ลำดับเหมือน WEEKLY:
     *   วันสุดท้ายของเดือน 23:55 → สร้าง record อัตโนมัติ (NO ACTION TAKEN)
     *   วันที่ 1 เวลา 00:10     → updateOverdueChecklistsMonth: mark overdue + reset เป็น PENDING
     */
    @Scheduled(cron = "0 55 23 L * *", zone = "Asia/Bangkok")
    @SchedulerLock(name = CHECKLIST_AUTO_SAVE_MONTHLY,
            lockAtLeastFor = BLOCKING_LOCK_AT_LEAST, lockAtMostFor = BLOCKING_LOCK_AT_MOST)
    public void autoSaveMonthlyChecklistRecords() {
        LocalDate today = LocalDate.now(ZONE);
        LocalDate first = today.withDayOfMonth(1);
        LocalDate last  = today.withDayOfMonth(today.lengthOfMonth());

        Instant from = first.atStartOfDay(ZONE).toInstant();
        Instant to   = last.atTime(LocalTime.MAX).atZone(ZONE).toInstant();

        runBlocking("AUTO-SAVE-MONTHLY", autoSave("MONTHLY", from, to));
    }

    private Mono<Void> autoSave(String period, Instant from, Instant to) {
        AtomicInteger created = new AtomicInteger();
        AtomicInteger skipped = new AtomicInteger();
        AtomicInteger failed  = new AtomicInteger();

        return template.select(
                        Query.query(
                                Criteria.where("machine_status").in(MachineStatus.activeDbValues())
                                        .and("reset_period").is(period)
                                        .and("check_status").is("PENDING")
                        ),
                        Machine.class
                )
                .concatMap(machine -> {
                    if (machine.getResponsiblePersonId() == null) {
                        skipped.incrementAndGet();
                        log.warn("[CHECKLIST-AUTO][{}] Skip machine id={} code={} — no responsible person",
                                period, machine.getId(), machine.getMachineCode());
                        return Mono.empty();
                    }
                    return hasChecklistRecord(machine, from, to)
                            .flatMap(exists -> {
                                if (exists) {
                                    skipped.incrementAndGet();
                                    log.debug("[CHECKLIST-AUTO][{}] Skip machine id={} code={} — record exists",
                                            period, machine.getId(), machine.getMachineCode());
                                    return Mono.<Void>empty();
                                }
                                return saveDefaultRecord(machine, created, skipped, failed);
                            });
                })
                .then()
                .doFinally(s -> log.info("[CHECKLIST-AUTO][{}] SUMMARY created={} skipped={} failed={}",
                        period, created.get(), skipped.get(), failed.get()));
    }

    // ─── CORE ─────────────────────────────────────────────────────────────────

    private Mono<Void> buildUpdateOverdue(String period) {
        return template.select(
                        Query.query(Criteria.where("reset_period").is(period)),
                        Machine.class
                )
                .map(Machine::getMachineCode)
                .collectList()
                .flatMap(machineCodes -> {
                    if (machineCodes.isEmpty()) {
                        log.info("[OVERDUE-{}] No machines with this reset period", period);
                        return Mono.empty();
                    }

                    // ทำทีละสถานะ (concatMap) — อยู่ใน transaction เดียวกัน ใช้ connection เดียวกัน
                    return Flux.fromIterable(PENDING_STATUSES)
                            .concatMap(status -> template.update(
                                    Query.query(
                                            Criteria.where("checklist_status").is(status)
                                                    .and("machine_code").in(machineCodes)
                                                    .and("recheck").is(true)
                                    ),
                                    Update.update("checklist_status", status + "-OVERDUE"),
                                    ChecklistRecord.class
                            ).doOnNext(count -> log.info("[OVERDUE-{}] {} → {}-OVERDUE: {} records updated",
                                    period, status, status, count)))
                            .then();
                });
    }

    private Mono<Void> resetMachineCheckStatus(String period) {
        return template.update(
                        Query.query(
                                Criteria.where("machine_status").in(MachineStatus.activeDbValues())
                                        .and("reset_period").is(period)
                        ),
                        Update.update("check_status", "PENDING"),
                        Machine.class
                )
                .doOnNext(count -> log.info("[RESET-{}] {} machines → check_status=PENDING", period, count))
                .then();
    }

    // ─── HELPERS ──────────────────────────────────────────────────────────────

    /**
     * รันงานแบบรอจนเสร็จ (กุญแจ ShedLock ถูกถือตลอดเวลาที่ทำงานจริง)
     * พร้อม timeout และ log เริ่ม/จบ/ล้มเหลว
     */
    private void runBlocking(String job, Mono<?> work) {
        long startedAt = System.currentTimeMillis();
        log.info("[CHECKLIST][{}] ▶ start", job);
        try {
            work.block(JOB_TIMEOUT);
            log.info("[CHECKLIST][{}] ■ done in {} ms", job, System.currentTimeMillis() - startedAt);
        } catch (Exception e) {
            log.error("[CHECKLIST][{}] ✗ Failed after {} ms: {}",
                    job, System.currentTimeMillis() - startedAt, e.getMessage(), e);
        }
    }

    /**
     * ใช้ CronExpression ของ Spring ตรวจว่า reset_time จะ "ทำงานในวันนี้" หรือไม่
     * รองรับรูปแบบ cron ครบ (MON-FRI, 1,15, ?, L, ช่วงเวลา, เดือน ฯลฯ)
     * เดิม parse เองได้แค่ * กับตัวเลขเดี่ยว และไม่รองรับ "?" → รายการแบบนั้นไม่เคยถูก reset
     */
    private boolean shouldResetToday(Object id, String cron, LocalDate today) {
        if (cron == null || cron.isBlank()) return false;
        try {
            CronExpression expr = CronExpression.parse(cron.trim());
            ZonedDateTime next = expr.next(today.atStartOfDay(ZONE).minusSeconds(1));
            return next != null && next.toLocalDate().equals(today);
        } catch (IllegalArgumentException e) {
            log.warn("[CHECKLIST][RESET] id={} invalid reset_time='{}' — skipped: {}", id, cron, e.getMessage());
            return false;
        }
    }

    private Mono<Boolean> hasChecklistRecord(Machine machine, Instant from, Instant to) {
        return template.count(
                Query.query(
                        Criteria.where("machine_code").is(machine.getMachineCode())
                                .and("created_by").is(machine.getResponsiblePersonId())
                                .and("recheck").is(true)
                                .and("check_type").is("GENERAL")
                                .and("created_at").between(from, to)
                ),
                ChecklistRecord.class
        ).map(count -> count > 0);
    }

    private Mono<Void> saveDefaultRecord(Machine machine,
                                         AtomicInteger created, AtomicInteger skipped, AtomicInteger failed) {
        return template.selectOne(
                        Query.query(Criteria.where("id").is(machine.getResponsiblePersonId())),
                        Member.class
                )
                .switchIfEmpty(Mono.defer(() -> {
                    skipped.incrementAndGet();
                    log.warn("[CHECKLIST-AUTO] Skip machine id={} code={} — member id={} not found",
                            machine.getId(), machine.getMachineCode(), machine.getResponsiblePersonId());
                    return Mono.empty();
                }))
                .flatMap(member -> {
                    String checklistStatus = machine.getSupervisorId() != null
                            ? "PENDING SUPERVISOR"
                            : "PENDING MANAGER";

                    ChecklistRecord record = ChecklistRecord.builder()
                            .checkType("GENERAL")
                            .recheck(true)
                            .machineCode(machine.getMachineCode())
                            .machineName(machine.getMachineName())
                            .machineStatus(machine.getMachineStatus())
                            .machineChecklist("")
                            .machineNote("Automatic recording")
                            .userId(member.getUserName())
                            .userName(machine.getResponsiblePersonName())
                            .supervisor(machine.getSupervisorId())
                            .dateSupervisorChecked(null)
                            .manager(machine.getManagerId())
                            .dateManagerChecked(null)
                            .checklistStatus(checklistStatus)
                            .reasonNotChecked("NO ACTION TAKEN")
                            .build();
                    record.setCreatedBy(machine.getResponsiblePersonId());

                    Mono<Void> updateMachine = template.update(Machine.class)
                            .matching(Query.query(Criteria.where("id").is(machine.getId())))
                            .apply(Update.update("check_status", checklistStatus)
                                    .set("machine_status", machine.getMachineStatus()))
                            .then();

                    // update เครื่อง + insert record ใน transaction เดียว
                    // เดิม: insert พัง → เครื่องถูกเปลี่ยนสถานะไปแล้วแต่ไม่มี record
                    TransactionalOperator tx = TransactionalOperator.create(transactionManager);

                    return tx.transactional(updateMachine.then(Mono.defer(() -> template.insert(record))))
                            .doOnSuccess(saved -> {
                                created.incrementAndGet();
                                log.info("[CHECKLIST-AUTO] Created auto record machine id={} code={} status={}",
                                        machine.getId(), machine.getMachineCode(), checklistStatus);
                            })
                            .onErrorResume(e -> {
                                if (isDuplicateKey(e)) {
                                    skipped.incrementAndGet();
                                    log.warn("[CHECKLIST-AUTO] Skip duplicate machine id={} code={} (record already exists)",
                                            machine.getId(), machine.getMachineCode());
                                } else {
                                    failed.incrementAndGet();
                                    log.error("[CHECKLIST-AUTO] ✗ Failed machine id={} code={}: {}",
                                            machine.getId(), machine.getMachineCode(), e.getMessage(), e);
                                }
                                return Mono.empty();
                            })
                            .then();
                });
    }

    private boolean isDuplicateKey(Throwable e) {
        if (e instanceof DuplicateKeyException) return true;
        String msg = e.getMessage();
        return msg != null && msg.toLowerCase().contains("duplicate key");
    }
}