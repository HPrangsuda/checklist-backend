package com.acme.checklist.scheduler;

import com.acme.checklist.entity.ChecklistRecord;
import com.acme.checklist.entity.Machine;
import com.acme.checklist.entity.Member;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.data.relational.core.query.Criteria;
import org.springframework.data.relational.core.query.Query;
import org.springframework.data.relational.core.query.Update;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.*;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class ChecklistScheduler {

    private final R2dbcEntityTemplate template;
    private static final ZoneId ZONE = ZoneId.of("Asia/Bangkok");

    // ─── WEEKLY ───────────────────────────────────────────────────────────────

    /**
     * ทุกวันจันทร์ 00:01 น. — mark overdue + reset check_status = PENDING
     * ต้องรันก่อน autoSaveWeeklyChecklistRecords (Friday 23:59)
     * ลำดับ: Monday 00:01 reset → Friday 23:59 auto-save สัปดาห์ถัดไป ✅
     */
    @Scheduled(cron = "0 1 0 * * MON", zone = "Asia/Bangkok")
    public void updateOverdueChecklistsWeek() {
        buildUpdateOverdue("WEEKLY")
                .then(resetMachineCheckStatus("WEEKLY"))
                .doOnSuccess(v -> log.info("[SCHEDULER] Weekly overdue + reset completed"))
                .doOnError(e -> log.error("[SCHEDULER] Weekly failed: {}", e.getMessage()))
                .block();
    }

    // ─── MONTHLY ──────────────────────────────────────────────────────────────

    /**
     * ทุกวันที่ 1 ของเดือน 00:00 น. — mark overdue + reset check_status = PENDING
     * ต้องรันก่อน autoSaveMonthlyChecklistRecords (วันที่ 1 23:55) ✅
     */
    @Scheduled(cron = "0 0 0 1 * *", zone = "Asia/Bangkok")
    public void updateOverdueChecklistsMonth() {
        buildUpdateOverdue("MONTHLY")
                .then(resetMachineCheckStatus("MONTHLY"))
                .doOnSuccess(v -> log.info("[SCHEDULER] Monthly overdue + reset completed"))
                .doOnError(e -> log.error("[SCHEDULER] Monthly failed: {}", e.getMessage()))
                .block();
    }

    // ─── AUTO SAVE WEEKLY ─────────────────────────────────────────────────────

    /**
     * ทุกวันศุกร์ 23:59 น. — บันทึก auto record สำหรับ machine ที่ยังไม่ได้เช็คในสัปดาห์นี้
     *
     * Window: จันทร์ 00:00 — ศุกร์ 23:59:59.999999999
     * เงื่อนไข: machine check_status = PENDING (ยังไม่ได้เช็คสัปดาห์นี้)
     */
    @Scheduled(cron = "0 55 3 * * SAT", zone = "Asia/Bangkok")
    public void autoSaveWeeklyChecklistRecords() {
        LocalDate today  = LocalDate.now(ZONE);
        LocalDate monday = today.with(DayOfWeek.MONDAY);
        LocalDate friday = today.with(DayOfWeek.FRIDAY);

        // Monday 00:00:00 → Saturday 00:00:00 - 1ns (= Friday 23:59:59.999999999)
        Instant startOfWeek = monday.atStartOfDay(ZONE).toInstant();
        Instant endOfWeek   = friday.atTime(LocalTime.MAX).atZone(ZONE).toInstant();

        log.info("[CHECKLIST-AUTO] Weekly run — week {} ~ {}", monday, friday);

        template.select(
                        Query.query(
                                Criteria.where("machine_status").in("OPERATIONAL", "NON-OPERATIONAL", "UNDER MAINTENANCE")
                                        .and("reset_period").is("WEEKLY")
                                        .and("check_status").is("PENDING")
                        ),
                        Machine.class
                )
                // FIX: ใช้ concatMap แทน flatMap เพื่อป้องกัน race condition
                // flatMap รัน concurrent หลาย thread พร้อมกัน → hasChecklistRecord อาจ return false
                // ก่อนที่ thread อื่นจะ insert เสร็จ → duplicate key
                .concatMap(machine -> {
                    if (machine.getResponsiblePersonId() == null) {
                        log.warn("[CHECKLIST-AUTO] Skip machine id={} code={} — responsiblePersonId is null",
                                machine.getId(), machine.getMachineCode());
                        return Mono.empty();
                    }
                    return hasChecklistRecord(machine, startOfWeek, endOfWeek)
                            .flatMap(exists -> {
                                if (exists) {
                                    log.info("[CHECKLIST-AUTO] Skip machine id={} code={} (record exists in this week)",
                                            machine.getId(), machine.getMachineCode());
                                    return Mono.<Void>empty();
                                }
                                return saveDefaultRecord(machine);
                            });
                })
                .then()
                .doOnSuccess(v -> log.info("[CHECKLIST-AUTO] Weekly auto-save completed"))
                .doOnError(e -> log.error("[CHECKLIST-AUTO] Weekly auto-save failed: {}", e.getMessage()))
                .block();
    }

    // ─── AUTO SAVE MONTHLY ────────────────────────────────────────────────────

    /**
     * ทุกวันที่ 1 ของเดือน 23:55 น. — บันทึก auto record สำหรับ machine ที่ยังไม่ได้เช็คในเดือนที่แล้ว
     *
     * ลำดับในวันที่ 1:
     *   00:00 → reset check_status = PENDING  (updateOverdueChecklistsMonth)
     *   23:55 → auto-save เดือนที่แล้ว        (autoSaveMonthlyChecklistRecords)
     *
     * Window: วันที่ 1 ของเดือนที่แล้ว 00:00 — วันสุดท้ายของเดือนที่แล้ว 23:59:59.999999999
     * ตรวจสอบว่า machine เคยมี record ในเดือนที่แล้วหรือไม่
     * ถ้าไม่มี → สร้าง auto record (created_at = now = วันที่ 1 เดือนนี้ ซึ่งถูกต้องตามเวลาจริงที่บันทึก)
     */
    @Scheduled(cron = "0 55 23 1 * *", zone = "Asia/Bangkok")
    public void autoSaveMonthlyChecklistRecords() {
        LocalDate today               = LocalDate.now(ZONE);
        LocalDate firstDayOfPrevMonth = today.minusMonths(1).withDayOfMonth(1);
        LocalDate lastDayOfPrevMonth  = today.minusMonths(1)
                .withDayOfMonth(today.minusMonths(1).lengthOfMonth());

        Instant startOfPrevMonth = firstDayOfPrevMonth.atStartOfDay(ZONE).toInstant();
        Instant endOfPrevMonth   = lastDayOfPrevMonth.atTime(LocalTime.MAX).atZone(ZONE).toInstant();

        log.info("[CHECKLIST-AUTO] Monthly run — {} ~ {}", firstDayOfPrevMonth, lastDayOfPrevMonth);

        template.select(
                        Query.query(
                                Criteria.where("machine_status").in("OPERATIONAL", "NON-OPERATIONAL", "UNDER MAINTENANCE")
                                        .and("reset_period").is("MONTHLY")
                                        .and("check_status").is("PENDING")
                        ),
                        Machine.class
                )
                // FIX: ใช้ concatMap แทน flatMap เพื่อป้องกัน race condition
                .concatMap(machine -> {
                    if (machine.getResponsiblePersonId() == null) {
                        log.warn("[CHECKLIST-AUTO] Skip machine id={} code={} — responsiblePersonId is null",
                                machine.getId(), machine.getMachineCode());
                        return Mono.empty();
                    }
                    return hasChecklistRecord(machine, startOfPrevMonth, endOfPrevMonth)
                            .flatMap(exists -> {
                                if (exists) {
                                    log.info("[CHECKLIST-AUTO] Skip machine id={} code={} (record exists in prev month)",
                                            machine.getId(), machine.getMachineCode());
                                    return Mono.<Void>empty();
                                }
                                return saveDefaultRecord(machine);
                            });
                })
                .then()
                .doOnSuccess(v -> log.info("[CHECKLIST-AUTO] Monthly auto-save completed"))
                .doOnError(e -> log.error("[CHECKLIST-AUTO] Monthly auto-save failed: {}", e.getMessage()))
                .block();
    }

    // ─── CORE ─────────────────────────────────────────────────────────────────

    private Mono<Void> buildUpdateOverdue(String period) {
        return template.select(
                        Query.query(Criteria.where("reset_period").is(period)),
                        Machine.class
                )
                .map(Machine::getId)
                .collectList()
                .flatMap(machineIds -> {
                    if (machineIds.isEmpty()) {
                        log.info("[OVERDUE-{}] No machines found for period", period);
                        return Mono.empty();
                    }

                    List<String> statuses = List.of("PENDING SUPERVISOR", "PENDING MANAGER");
                    return Flux.fromIterable(statuses)
                            .flatMap(status -> {
                                Query query = Query.query(
                                        Criteria.where("checklist_status").is(status)
                                                .and("machine_id").in(machineIds)
                                );
                                Update update = Update.update("checklist_status", status + "-OVERDUE");
                                return template.update(query, update, ChecklistRecord.class);
                            })
                            .then();
                });
    }

    private Mono<Void> resetMachineCheckStatus(String period) {
        return template.update(
                Query.query(
                        Criteria.where("machine_status").in("OPERATIONAL", "NON-OPERATIONAL", "UNDER MAINTENANCE")
                                .and("reset_period").is(period)
                ),
                Update.update("check_status", "PENDING"),
                Machine.class
        ).then();
    }

    // ─── HELPERS ──────────────────────────────────────────────────────────────

    /**
     * ตรวจสอบว่ามี checklist record ของ machine นี้ในช่วงเวลาที่กำหนดหรือไม่
     *
     * FIX: เปลี่ยนจาก .and("created_at").greaterThanOrEquals(from) + .and("created_at").lessThanOrEquals(to)
     *      ซึ่ง R2DBC จะ override field เดิม ทำให้ condition แรกหายไป
     *      → ใช้ .and("created_at").between(from, to) แทน (inclusive both ends)
     */
    private Mono<Boolean> hasChecklistRecord(Machine machine, Instant from, Instant to) {
        return template.count(
                Query.query(
                        Criteria.where("machine_code").is(machine.getMachineCode())
                                .and("created_by").is(machine.getResponsiblePersonId())
                                .and("recheck").is(true)
                                .and("check_type").is("GENERAL")
                                .and("created_at").between(from, to)   // ← FIX: ใช้ between แทน 2 conditions
                ),
                ChecklistRecord.class
        ).map(count -> {
            log.debug("[CHECKLIST-AUTO] hasChecklistRecord machine={} count={} from={} to={}",
                    machine.getMachineCode(), count, from, to);
            return count > 0;
        });
    }

    private Mono<Void> saveDefaultRecord(Machine machine) {
        if (machine.getResponsiblePersonId() == null) {
            log.warn("[CHECKLIST-AUTO] Skip machine id={} code={} — responsiblePersonId is null",
                    machine.getId(), machine.getMachineCode());
            return Mono.empty();
        }

        return template.selectOne(
                        Query.query(Criteria.where("id").is(machine.getResponsiblePersonId())),
                        Member.class
                )
                .switchIfEmpty(Mono.defer(() -> {
                    log.warn("[CHECKLIST-AUTO] Skip machine id={} code={} — member id={} not found in DB",
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

                    return template.insert(record)
                            .flatMap(saved -> template.update(Machine.class)
                                    .matching(Query.query(
                                            Criteria.where("id").is(machine.getId())
                                    ))
                                    .apply(Update.update("check_status", checklistStatus)
                                            .set("machine_status", machine.getMachineStatus()))
                                    .then())
                            .doOnSuccess(v -> log.info("[CHECKLIST-AUTO] Created auto record machine id={} code={} status={}",
                                    machine.getId(), machine.getMachineCode(), checklistStatus))
                            .onErrorResume(e -> {
                                log.error("[CHECKLIST-AUTO] Failed machine id={} code={}: {}",
                                        machine.getId(), machine.getMachineCode(), e.getMessage());
                                return Mono.empty();
                            });
                });
    }
}