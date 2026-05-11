package com.acme.checklist.scheduler;

import com.acme.checklist.entity.ChecklistRecord;
import com.acme.checklist.entity.Machine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.data.relational.core.query.Criteria;
import org.springframework.data.relational.core.query.Query;
import org.springframework.data.relational.core.query.Update;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class ChecklistScheduler {
    private final R2dbcEntityTemplate template;

    @Scheduled(cron = "0 1 0 * * MON")
    public void updateOverdueChecklistsWeek() {
        buildUpdateOverdue("WEEKLY").block();
    }

    @Scheduled(cron = "0 0 0 1 * *")
    public void updateOverdueChecklistsMonth() {
        buildUpdateOverdue("MONTHLY").block();
    }

    private Mono<Void> buildUpdateOverdue(String period) {
        Query query = Query.query(
                Criteria.where("checklist_status").in("PENDING SUPERVISOR", "PENDING MANAGER")
                        .and("reset_period").is(period)
        );
        return template.select(query, ChecklistRecord.class)
                .flatMap(record -> {
                    Update update = Update.update("checklist_status", record.getChecklistStatus() + "-OVERDUE");
                    return template.update(
                            Query.query(Criteria.where("id").is(record.getId())),
                            update,
                            ChecklistRecord.class
                    ).then(
                            template.update(
                                    Query.query(Criteria.where("machine_code").is(record.getMachineCode())),
                                    Update.update("check_status", "PENDING"),
                                    Machine.class
                            )
                    );
                })
                .onErrorContinue((e, obj) -> log.error("[OVERDUE-{}] Skipping record: {}", period, e.getMessage()))
                .then();
    }

    @Scheduled(cron = "0 59 23 * * FRI", zone = "Asia/Bangkok")
    public void autoSaveWeeklyChecklistRecords() {
        ZoneId zone = ZoneId.of("Asia/Bangkok");
        LocalDate today  = LocalDate.now(zone);
        LocalDate monday = today.with(DayOfWeek.MONDAY);
        LocalDate friday = today.with(DayOfWeek.FRIDAY);

        Instant startOfWeek = monday.atStartOfDay(zone).toInstant();
        Instant endOfWeek   = friday.atTime(23, 59, 59).atZone(zone).toInstant();

        log.info("[CHECKLIST-AUTO] Weekly run — week {} ~ {}", monday, friday);

        template.select(
                        Query.query(
                                Criteria.where("machine_status").in("OPERATIONAL", "NON-OPERATIONAL", "UNDER MAINTENANCE")
                                        .and("reset_period").is("WEEKLY")
                        ),
                        Machine.class
                )
                .flatMap(machine -> hasChecklistRecord(machine, startOfWeek, endOfWeek)
                        .flatMap(exists -> {
                            if (exists) {
                                log.info("[CHECKLIST-AUTO] Skip machine={} (record exists)", machine.getMachineCode());
                                return Mono.empty();
                            }
                            return saveDefaultRecord(machine);
                        })
                )
                .onErrorContinue((e, obj) -> log.error("[CHECKLIST-AUTO] Weekly error: {}", e.getMessage()))
                .doOnComplete(() -> log.info("[CHECKLIST-AUTO] Weekly run completed"))
                .then()
                .block();
    }

    @Scheduled(cron = "0 59 23 1 * *", zone = "Asia/Bangkok")
    public void autoSaveMonthlyChecklistRecords() {
        ZoneId zone = ZoneId.of("Asia/Bangkok");

        LocalDate today                = LocalDate.now(zone);
        LocalDate firstDayOfPrevMonth  = today.minusMonths(1).withDayOfMonth(1);
        LocalDate lastDayOfPrevMonth   = today.minusMonths(1).withDayOfMonth(today.minusMonths(1).lengthOfMonth());

        Instant startOfPrevMonth = firstDayOfPrevMonth.atStartOfDay(zone).toInstant();
        Instant endOfPrevMonth   = lastDayOfPrevMonth.atTime(23, 59, 59).atZone(zone).toInstant();

        log.info("[CHECKLIST-AUTO] Monthly run — {} ~ {}", firstDayOfPrevMonth, lastDayOfPrevMonth);

        template.select(
                        Query.query(
                                Criteria.where("machine_status").in("OPERATIONAL", "NON-OPERATIONAL", "UNDER MAINTENANCE")
                                        .and("reset_period").is("MONTHLY")
                        ),
                        Machine.class
                )
                .flatMap(machine -> hasChecklistRecord(machine, startOfPrevMonth, endOfPrevMonth)
                        .flatMap(exists -> {
                            if (exists) {
                                log.info("[CHECKLIST-AUTO] Skip machine={} (record exists)", machine.getMachineCode());
                                return Mono.empty();
                            }
                            return saveDefaultRecord(machine);
                        })
                )
                .onErrorContinue((e, obj) -> log.error("[CHECKLIST-AUTO] Monthly error: {}", e.getMessage()))
                .doOnComplete(() -> log.info("[CHECKLIST-AUTO] Monthly run completed"))
                .then()
                .block();
    }

    // ─── HELPERS ──────────────────────────────────────────────────────────────

    private Mono<Boolean> hasChecklistRecord(Machine machine, Instant from, Instant to) {
        return template.count(
                Query.query(
                        Criteria.where("machine_code").is(machine.getMachineCode())
                                .and("user_id").is(String.valueOf(machine.getResponsiblePersonId()))
                                .and("created_at").greaterThanOrEquals(from)
                                .and("created_at").lessThanOrEquals(to)
                ),
                ChecklistRecord.class
        ).map(count -> count > 0);
    }

    private Mono<Void> saveDefaultRecord(Machine machine) {
        if (machine.getResponsiblePersonId() == null) {
            log.warn("[CHECKLIST-AUTO] Skip machine={} — responsiblePersonId is null", machine.getMachineCode());
            return Mono.empty();
        }

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
                .userId(String.valueOf(machine.getResponsiblePersonId()))
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
                        .matching(Query.query(Criteria.where("machine_code").is(machine.getMachineCode())))
                        .apply(Update.update("check_status", checklistStatus)
                                .set("machine_status", machine.getMachineStatus()))
                        .then())
                .doOnSuccess(v -> log.info("[CHECKLIST-AUTO] ✓ Auto-saved machineCode={}", machine.getMachineCode()))
                .doOnError(e -> log.error("[CHECKLIST-AUTO] ✗ Failed machineCode={}: {}", machine.getMachineCode(), e.getMessage()))
                .onErrorResume(e -> Mono.empty());
    }
}
