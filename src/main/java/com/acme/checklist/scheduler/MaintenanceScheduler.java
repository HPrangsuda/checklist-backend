package com.acme.checklist.scheduler;

import com.acme.checklist.entity.Machine;
import com.acme.checklist.entity.MaintenanceRecord;
import com.acme.checklist.entity.Department;
import com.acme.checklist.entity.Member;
import com.acme.checklist.entity.enums.RoleType;
import com.acme.checklist.entity.enums.MaintenanceType;
import com.acme.checklist.service.EmailService;
import com.acme.checklist.service.LarkService;
import io.r2dbc.spi.Row;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.data.domain.Sort;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.data.relational.core.query.Criteria;
import org.springframework.data.relational.core.query.Query;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.Exceptions;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;
import reactor.util.retry.RetryBackoffSpec;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.acme.checklist.scheduler.SchedulerLocks.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class MaintenanceScheduler {

    private final R2dbcEntityTemplate template;
    private final EmailService emailService;
    private final LarkService larkService;
    private final ReactiveTransactionManager transactionManager;

    // ======================================================================
    // Config
    // ======================================================================

    private static final ZoneId ZONE = ZoneId.of("Asia/Bangkok");
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    /** เริ่มแจ้งเตือนเมื่อเหลือไม่เกินกี่วันก่อนถึง due date */
    private static final int DUE_WITHIN_DAYS = 45;
    private static final int RED_WITHIN_DAYS = 7;
    private static final int YELLOW_WITHIN_DAYS = 14;

    /** จำนวนข้อความที่ส่งพร้อมกันสูงสุด (กัน rate limit ของ Lark / SMTP) */
    private static final int SEND_CONCURRENCY = 3;

    /** จำนวนรายการสูงสุดต่อ 1 การ์ด Lark — เกินจะแบ่งเป็นหลายการ์ด */
    private static final int MAX_RECORDS_PER_CARD = 30;

    /** retry สูงสุด 3 ครั้ง รอ 2s → 4s → 8s (ไม่เกิน 10s) */
    private static final int MAX_RETRIES = 3;

    private static final MaintenanceType DEFAULT_TYPE = MaintenanceType.PREVENTIVE;

    // ======================================================================
    // Job 1: คัดลอก maintenance record ไปปีถัดไป (25 ธ.ค. 01:00)
    // ======================================================================

    @Scheduled(cron = "0 0 1 25 12 *", zone = "Asia/Bangkok")
    @SchedulerLock(name = MAINTENANCE_COPY_NEXT_YEAR,
            lockAtLeastFor = COPY_LOCK_AT_LEAST, lockAtMostFor = COPY_LOCK_AT_MOST)
    public void createNextYearMaintenanceRecords() {
        int currentYear = LocalDate.now(ZONE).getYear();
        int nextYear = currentYear + 1;
        DatabaseClient db = template.getDatabaseClient();
        TransactionalOperator tx = TransactionalOperator.create(transactionManager);

        Mono<Void> copy = db.sql("SELECT COUNT(*) AS cnt FROM maintenance_record WHERE years = $1")
                .bind(0, String.valueOf(nextYear))
                .map((row, meta) -> Optional.ofNullable(row.get("cnt", Long.class)).orElse(0L))
                .one()
                .defaultIfEmpty(0L)
                .flatMap(existing -> {
                    if (existing > 0) {
                        log.warn("[M-COPY] Records for year={} already exist ({} records) — skipping", nextYear, existing);
                        return Mono.empty();
                    }

                    // อ่านให้ครบก่อน แล้วค่อย insert (ห้าม insert ระหว่างที่ยัง stream ผล SELECT บน connection เดียวกัน)
                    return db.sql("SELECT * FROM maintenance_record WHERE years = $1")
                            .bind(0, String.valueOf(currentYear))
                            .map((row, meta) -> toNextYearRecord(row, nextYear))
                            .all()
                            .collectList()
                            .flatMap(records -> {
                                if (records.isEmpty()) {
                                    log.info("[M-COPY] No maintenance records found for year={} — nothing to copy", currentYear);
                                    return Mono.empty();
                                }
                                log.info("[M-COPY] Creating {} maintenance records for year={}", records.size(), nextYear);

                                return Flux.fromIterable(records)
                                        .concatMap(r -> insertCopy(db, r, nextYear))
                                        .count()
                                        .doOnNext(total -> log.info("[M-COPY] ✓ Created {} records for year={}", total, nextYear))
                                        .then();
                            });
                });

        // ทั้งหมดอยู่ใน transaction เดียว: insert พังกลางทาง → rollback ทั้งหมด รอบหน้าทำใหม่ได้
        tx.transactional(copy)
                .onErrorResume(e -> {
                    log.error("[M-COPY] ✗ Failed — rolled back, no records created for year={}: {}",
                            nextYear, e.getMessage(), e);
                    return Mono.empty();
                })
                .subscribe();
    }

    private MaintenanceRecord toNextYearRecord(Row row, int nextYear) {
        MaintenanceRecord r = new MaintenanceRecord();
        r.setMachineCode(row.get("machine_code", String.class));
        r.setMachineName(row.get("machine_name", String.class));
        r.setRound(row.get("round", Integer.class));
        r.setNote(row.get("note", String.class));
        r.setMaintenanceType(parseMaintenanceType(row.get("maintenance_type", String.class)));
        LocalDate dueDate = row.get("due_date", LocalDate.class);
        r.setDueDate(dueDate != null ? dueDate.withYear(nextYear) : null);   // 29 ก.พ. → 28 ก.พ. อัตโนมัติ
        LocalDate planDate = row.get("plan_date", LocalDate.class);
        r.setPlanDate(planDate != null ? planDate.withYear(nextYear) : null);
        r.setYears(String.valueOf(nextYear));
        // start_date, actual_date, status, maintenance_by, responsible_maintenance, attachment
        // ไม่ได้ถูก insert → เป็น null ในปีใหม่โดยอัตโนมัติ
        return r;
    }

    /** ค่าใน DB ที่ไม่ตรง enum ไม่ทำให้ทั้ง job พัง — ใช้ค่า default แล้ว log เตือน */
    private MaintenanceType parseMaintenanceType(String value) {
        if (value == null || value.isBlank()) return DEFAULT_TYPE;
        try {
            return MaintenanceType.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            log.warn("[M-COPY] Unknown maintenance_type='{}' — using {}", value, DEFAULT_TYPE);
            return DEFAULT_TYPE;
        }
    }

    private Mono<Long> insertCopy(DatabaseClient db, MaintenanceRecord r, int nextYear) {
        DatabaseClient.GenericExecuteSpec spec = db.sql("""
                INSERT INTO maintenance_record
                (machine_code, machine_name, years, round, due_date, plan_date, note, maintenance_type)
                VALUES ($1, $2, $3, $4, $5, $6, $7, $8)
                """);

        spec = bindNullable(spec, 0, r.getMachineCode(), String.class);
        spec = bindNullable(spec, 1, r.getMachineName(), String.class);
        spec = spec.bind(2, String.valueOf(nextYear));
        spec = bindNullable(spec, 3, r.getRound(), Integer.class);
        spec = bindNullable(spec, 4, r.getDueDate(), LocalDate.class);
        spec = bindNullable(spec, 5, r.getPlanDate(), LocalDate.class);
        spec = bindNullable(spec, 6, r.getNote(), String.class);
        spec = spec.bind(7, (r.getMaintenanceType() != null ? r.getMaintenanceType() : DEFAULT_TYPE).name());

        return spec.then()
                .doOnSuccess(v -> log.debug("[M-COPY] ✓ Inserted machineCode={}, round={}, year={}",
                        r.getMachineCode(), r.getRound(), nextYear))
                .doOnError(e -> log.error("[M-COPY] ✗ Insert failed machineCode={}, round={}: {}",
                        r.getMachineCode(), r.getRound(), e.getMessage()))
                .thenReturn(1L);
    }

    private <T> DatabaseClient.GenericExecuteSpec bindNullable(
            DatabaseClient.GenericExecuteSpec spec, int index, T value, Class<T> type) {
        return value != null ? spec.bind(index, value) : spec.bindNull(index, type);
    }

    // ======================================================================
    // Job 2–3: แจ้งเตือน
    // ======================================================================

    /** ผู้รับผิดชอบ + หัวหน้างาน: จันทร์–ศุกร์ 09:00 */
    @Scheduled(cron = "0 0 9 * * MON-FRI", zone = "Asia/Bangkok")
    @SchedulerLock(name = MAINTENANCE_NOTIFY_DAILY,
            lockAtLeastFor = NOTIFY_LOCK_AT_LEAST, lockAtMostFor = NOTIFY_LOCK_AT_MOST)
    public void sendDailyToResponsibleAndSupervisor() {
        runNotifyJob("RESPONSIBLE_SUPERVISOR",
                records -> notifyByMachineRole(records, Role.RESPONSIBLE_SUPERVISOR));
    }

    /** ผู้จัดการ: จันทร์และพุธ 09:15 */
    @Scheduled(cron = "0 15 9 * * MON,WED", zone = "Asia/Bangkok")
    @SchedulerLock(name = MAINTENANCE_NOTIFY_MANAGER,
            lockAtLeastFor = NOTIFY_LOCK_AT_LEAST, lockAtMostFor = NOTIFY_LOCK_AT_MOST)
    public void sendWeeklyToManager() {
        runNotifyJob("MANAGER",
                records -> notifyByMachineRole(records, Role.MANAGER));
    }

    /**
     * ADMIN และ DEPARTMENT_ADMIN: ค่าเริ่มต้นทุกวันจันทร์ 09:00
     * - ADMIN            : ได้รับทุกรายการ
     * - DEPARTMENT_ADMIN : เฉพาะเครื่องที่อยู่แผนกเดียวกับตัวเอง
     * ทำทีละ role และแยก error กัน
     *
     * เปลี่ยนเวลาได้โดยไม่ต้องแก้โค้ด ผ่าน property scheduler.maintenance.admins-cron
     * (เช่นตอนทดสอบ) — ไม่ตั้งค่าจะใช้ 0 0 9 * * MON
     */
    @Scheduled(cron = "${scheduler.maintenance.admins-cron:0 0 9 * * MON}", zone = "Asia/Bangkok")
    @SchedulerLock(name = MAINTENANCE_NOTIFY_ADMINS,
            lockAtLeastFor = NOTIFY_LOCK_AT_LEAST, lockAtMostFor = NOTIFY_LOCK_AT_MOST)
    public void sendWeeklyToAdmins() {
        runNotifyJob("ADMINS", records ->
                notifyAdmins(records, Role.ADMIN)
                        .onErrorResume(e -> logRoleFailure(Role.ADMIN, e))
                        .then(Mono.defer(() -> notifyAdmins(records, Role.DEPARTMENT_ADMIN))
                                .onErrorResume(e -> logRoleFailure(Role.DEPARTMENT_ADMIN, e))));
    }

    private Mono<Void> logRoleFailure(Role role, Throwable e) {
        log.error("[M-JOB][{}] ✗ Failed: {}", role, e.getMessage(), e);
        return Mono.empty();
    }

    private void runNotifyJob(String jobName, Function<List<MaintenanceRecord>, Mono<Void>> action) {
        long startedAt = System.currentTimeMillis();
        log.info("[M-JOB][{}] ▶ start", jobName);

        fetchDueMaintenances()
                .collectList()
                .flatMap(records -> {
                    if (records.isEmpty()) {
                        log.info("[M-JOB][{}] No maintenance due within {} days — nothing to send", jobName, DUE_WITHIN_DAYS);
                        return Mono.empty();
                    }
                    log.info("[M-JOB][{}] {} maintenance records due within {} days", jobName, records.size(), DUE_WITHIN_DAYS);
                    return action.apply(records);
                })
                .doOnSuccess(v -> log.info("[M-JOB][{}] ■ done in {} ms", jobName, System.currentTimeMillis() - startedAt))
                .onErrorResume(e -> {
                    log.error("[M-JOB][{}] ✗ Failed after {} ms: {}",
                            jobName, System.currentTimeMillis() - startedAt, e.getMessage(), e);
                    return Mono.empty();
                })
                .subscribe();
    }

    private Flux<MaintenanceRecord> fetchDueMaintenances() {
        LocalDate dueLimit = LocalDate.now(ZONE).plusDays(DUE_WITHIN_DAYS);

        return template.select(
                        Query.query(
                                Criteria.where("due_date").lessThanOrEquals(dueLimit)
                                        .and("start_date").isNull()
                        ).sort(Sort.by("due_date").ascending()),
                        MaintenanceRecord.class
                )
                .filter(r -> r.getDueDate() != null)
                .doOnNext(r -> log.debug("[M-FETCH] id={}, machineCode={}, round={}, dueDate={}",
                        r.getId(), r.getMachineCode(), r.getRound(), r.getDueDate()));
    }

    // ======================================================================
    // หาผู้รับจาก machine
    // ======================================================================

    private Mono<Void> notifyByMachineRole(List<MaintenanceRecord> records, Role role) {

        Map<String, List<MaintenanceRecord>> byMachineCode = records.stream()
                .filter(r -> r.getMachineCode() != null)
                .collect(Collectors.groupingBy(MaintenanceRecord::getMachineCode));

        if (byMachineCode.isEmpty()) return Mono.empty();

        return template.select(
                        Query.query(Criteria.where("machine_code").in(byMachineCode.keySet())),
                        Machine.class
                )
                .collectList()
                .flatMap(machines -> {
                    Set<String> foundCodes = machines.stream()
                            .map(Machine::getMachineCode)
                            .filter(Objects::nonNull)
                            .collect(Collectors.toSet());
                    byMachineCode.keySet().stream()
                            .filter(code -> !foundCodes.contains(code))
                            .forEach(code -> log.warn("[M-{}] machineCode={} has maintenance due but no machine record — nobody notified",
                                    role, code));

                    Map<Long, Map<Long, MaintenanceRecord>> byPersonMap = new HashMap<>();

                    machines.forEach(machine -> {
                        List<MaintenanceRecord> machineRecords =
                                byMachineCode.getOrDefault(machine.getMachineCode(), List.of());
                        if (machineRecords.isEmpty()) return;

                        List<Long> personIds = switch (role) {
                            case RESPONSIBLE_SUPERVISOR -> Stream.of(
                                            machine.getResponsiblePersonId(),
                                            machine.getSupervisorId())
                                    .filter(Objects::nonNull)
                                    .distinct()
                                    .toList();
                            case MANAGER -> Stream.of(machine.getManagerId())
                                    .filter(Objects::nonNull)
                                    .toList();
                            // admin roles ไม่ได้ผูกกับ machine — ใช้ notifyAdmins แทน
                            case DEPARTMENT_ADMIN, ADMIN -> List.of();
                        };

                        if (personIds.isEmpty()) {
                            log.warn("[M-{}] machineCode={} has no assigned person for this role — nobody notified",
                                    role, machine.getMachineCode());
                            return;
                        }

                        personIds.forEach(personId -> {
                            Map<Long, MaintenanceRecord> existing =
                                    byPersonMap.computeIfAbsent(personId, k -> new LinkedHashMap<>());
                            machineRecords.forEach(r -> existing.put(r.getId(), r));
                        });
                    });

                    Map<Long, List<MaintenanceRecord>> byPerson = new HashMap<>();
                    byPersonMap.forEach((personId, recs) -> byPerson.put(personId, new ArrayList<>(recs.values())));

                    if (byPerson.isEmpty()) {
                        log.warn("[M-{}] No recipients resolved — nothing to send", role);
                        return Mono.empty();
                    }
                    return sendToMembers(byPerson, role);
                });
    }

    // ======================================================================
    // หาผู้รับ: ADMIN / DEPARTMENT_ADMIN
    // - role อยู่ในคอลัมน์ member.role_type (enum RoleType) และนับเฉพาะ status = ACTIVE
    // - แผนกของ admin = member.department_id, แผนกของเครื่อง = machine.department
    //   เทียบกันผ่านตาราง department (รองรับทั้งรหัส / ชื่อ / id) ด้วย DepartmentMatcher
    // ======================================================================

    private Mono<Void> notifyAdmins(List<MaintenanceRecord> records, Role role) {

        Mono<List<Member>> adminsMono = template.select(
                Query.query(Criteria.where("role_type").is(toRoleType(role).name())
                        .and("status").is("ACTIVE")),
                Member.class
        ).collectList();

        if (role == Role.ADMIN) {
            return adminsMono.flatMap(admins -> {
                if (admins.isEmpty()) {
                    log.warn("[M-{}] No active members with this role — skipping", role);
                    return Mono.empty();
                }
                Map<Long, List<MaintenanceRecord>> byPerson = new HashMap<>();
                admins.forEach(a -> byPerson.put(a.getId(), records));
                return sendToMembers(byPerson, role);
            });
        }

        Set<String> machineCodes = records.stream()
                .map(MaintenanceRecord::getMachineCode)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        if (machineCodes.isEmpty()) return Mono.empty();

        Mono<List<Machine>> machinesMono = template.select(
                Query.query(Criteria.where("machine_code").in(machineCodes)),
                Machine.class
        ).collectList();

        Mono<List<Department>> departmentsMono = template.select(Query.empty(), Department.class).collectList();

        return Mono.zip(adminsMono, machinesMono, departmentsMono)
                .flatMap(tuple -> {
                    List<Member> admins = tuple.getT1();
                    List<Machine> machines = tuple.getT2();
                    DepartmentMatcher matcher = DepartmentMatcher.of(tuple.getT3());

                    if (admins.isEmpty()) {
                        log.warn("[M-{}] No active members with this role — skipping", role);
                        return Mono.empty();
                    }

                    // แผนก (key กลาง) → machine codes
                    Map<String, Set<String>> codesByDept = new HashMap<>();
                    Set<String> unresolvedMachineDepts = new TreeSet<>();
                    for (Machine m : machines) {
                        if (m.getMachineCode() == null) continue;
                        String deptKey = matcher.resolve(m.getDepartment());
                        if (deptKey == null) {
                            unresolvedMachineDepts.add(String.valueOf(m.getDepartment()));
                            continue;
                        }
                        codesByDept.computeIfAbsent(deptKey, k -> new HashSet<>()).add(m.getMachineCode());
                    }
                    if (!unresolvedMachineDepts.isEmpty()) {
                        log.warn("[M-{}] machine.department values not found in department table: {}",
                                role, unresolvedMachineDepts);
                    }

                    Map<Long, List<MaintenanceRecord>> byPerson = new HashMap<>();

                    admins.forEach(admin -> {
                        String deptKey = matcher.resolve(admin.getDepartmentId());
                        if (deptKey == null) {
                            log.warn("[M-{}] memberId={} department_id='{}' not found in department table — skipped",
                                    role, admin.getId(), admin.getDepartmentId());
                            return;
                        }
                        Set<String> deptCodes = codesByDept.getOrDefault(deptKey, Set.of());
                        List<MaintenanceRecord> deptRecords = records.stream()
                                .filter(r -> r.getMachineCode() != null && deptCodes.contains(r.getMachineCode()))
                                .toList();
                        if (deptRecords.isEmpty()) {
                            log.info("[M-{}] memberId={} (department_id={}) — nothing due in department",
                                    role, admin.getId(), admin.getDepartmentId());
                            return;
                        }
                        byPerson.put(admin.getId(), deptRecords);
                    });

                    if (byPerson.isEmpty()) return Mono.empty();
                    return sendToMembers(byPerson, role);
                });
    }

    private RoleType toRoleType(Role role) {
        return switch (role) {
            case ADMIN -> RoleType.ADMIN;
            case DEPARTMENT_ADMIN -> RoleType.DEPARTMENT_ADMIN;
            default -> throw new IllegalArgumentException("Not an admin role: " + role);
        };
    }

    // ======================================================================
    // ส่งข้อความ (Email + Lark)
    // ======================================================================

    private Mono<Void> sendToMembers(Map<Long, List<MaintenanceRecord>> rawByPerson, Role role) {

        Comparator<MaintenanceRecord> order = Comparator
                .comparing(MaintenanceRecord::getDueDate)
                .thenComparing(MaintenanceRecord::getMachineCode, Comparator.nullsLast(Comparator.naturalOrder()));
        Map<Long, List<MaintenanceRecord>> byPerson = new HashMap<>();
        rawByPerson.forEach((id, recs) -> byPerson.put(id, recs.stream().sorted(order).toList()));

        Set<Long> memberIds = byPerson.keySet();
        log.info("[M-SEND][{}] Recipients={}", role, memberIds.size());

        return template.select(
                        Query.query(Criteria.where("id").in(memberIds)),
                        Member.class
                )
                .collectList()
                .flatMap(members -> {
                    if (members.isEmpty()) {
                        log.warn("[M-SEND][{}] No members found for memberIds={} — skipping", role, memberIds);
                        return Mono.empty();
                    }

                    Set<Long> foundIds = members.stream().map(Member::getId).collect(Collectors.toSet());
                    memberIds.stream()
                            .filter(id -> !foundIds.contains(id))
                            .forEach(id -> log.warn("[M-SEND][{}] memberId={} not found in member table — skipped", role, id));

                    SendStats stats = new SendStats();
                    LocalDate today = LocalDate.now(ZONE);

                    return Mono.when(
                                    sendEmails(members, byPerson, role, today, stats),
                                    sendLarkMessages(members, byPerson, role, today, stats))
                            .doFinally(signal -> log.info(
                                    "[M-SEND][{}] SUMMARY members={} | email ok={} fail={} skip={} | lark cards ok={} fail={} | lark members skip={}",
                                    role, members.size(),
                                    stats.emailOk.get(), stats.emailFail.get(), stats.emailSkip.get(),
                                    stats.larkOk.get(), stats.larkFail.get(), stats.larkSkip.get()));
                });
    }

    // ---------------------------------------------------------------- Email

    private Mono<Void> sendEmails(List<Member> members,
                                  Map<Long, List<MaintenanceRecord>> byPerson,
                                  Role role,
                                  LocalDate today,
                                  SendStats stats) {
        String subject = "Maintenance Due Date Reminder - " + today.format(DateTimeFormatter.ISO_DATE);

        return Flux.fromIterable(members)
                .flatMap(member -> {
                    List<MaintenanceRecord> records = byPerson.getOrDefault(member.getId(), List.of());
                    if (records.isEmpty()) return Mono.empty();

                    if (member.getEmail() == null || member.getEmail().isBlank()) {
                        stats.emailSkip.incrementAndGet();
                        log.warn("[M-SEND][{}] memberId={} has no email — skipped", role, member.getId());
                        return Mono.empty();
                    }

                    String content = buildEmailContent(records, today);

                    return emailService.sendReminder(member.getEmail(), subject, content)
                            .retryWhen(retrySpec("email memberId=" + member.getId()))
                            .doOnSuccess(v -> {
                                stats.emailOk.incrementAndGet();
                                log.info("[M-SEND][{}] ✓ Email sent memberId={}, records={}",
                                        role, member.getId(), records.size());
                            })
                            .onErrorResume(e -> {
                                stats.emailFail.incrementAndGet();
                                log.error("[M-SEND][{}] ✗ Email FAILED memberId={}, email={}: {}",
                                        role, member.getId(), member.getEmail(), rootMessage(e));
                                return Mono.empty();
                            });
                }, SEND_CONCURRENCY)
                .then();
    }

    // ---------------------------------------------------------------- Lark

    private Mono<Void> sendLarkMessages(List<Member> members,
                                        Map<Long, List<MaintenanceRecord>> byPerson,
                                        Role role,
                                        LocalDate today,
                                        SendStats stats) {

        Map<String, List<Long>> memberIdsByMobileKey = new LinkedHashMap<>();
        Map<String, String> e164ByMobileKey = new LinkedHashMap<>();

        members.forEach(m -> {
            if (byPerson.getOrDefault(m.getId(), List.of()).isEmpty()) return;

            String e164 = toInternationalFormat(m.getMobiles());
            if (e164 == null) {
                stats.larkSkip.incrementAndGet();
                log.warn("[M-SEND][{}] memberId={} has no valid mobile — Lark skipped", role, m.getId());
                return;
            }
            String key = mobileKey(e164);
            memberIdsByMobileKey.computeIfAbsent(key, k -> new ArrayList<>()).add(m.getId());
            e164ByMobileKey.putIfAbsent(key, e164);
        });

        if (memberIdsByMobileKey.isEmpty()) {
            log.warn("[M-SEND][{}] No valid mobiles — skipping Lark", role);
            return Mono.empty();
        }

        Mono<Map<String, String>> openIdByKeyMono = Flux.fromIterable(new ArrayList<>(e164ByMobileKey.values()))
                .buffer(LarkService.MAX_MOBILES_PER_REQUEST)
                .concatMap(batch -> larkService.batchGetOpenIdsByMobile(batch)
                        .retryWhen(retrySpec("lark openId lookup (" + batch.size() + " mobiles)"))
                        .onErrorResume(e -> {
                            log.error("[M-SEND][{}] ✗ Lark openId lookup FAILED for {} mobiles: {}",
                                    role, batch.size(), rootMessage(e));
                            return Mono.just(Map.<String, String>of());
                        }))
                .collect(() -> new HashMap<String, String>(),
                        (acc, part) -> part.forEach((mobile, openId) -> {
                            String key = mobileKey(toInternationalFormat(mobile));
                            if (key != null) acc.put(key, openId);
                        }))
                .map(Collections::unmodifiableMap);

        return openIdByKeyMono.flatMap(openIdByKey ->
                Flux.fromIterable(memberIdsByMobileKey.entrySet())
                        .flatMapIterable(entry -> entry.getValue().stream()
                                .map(memberId -> Map.entry(entry.getKey(), memberId))
                                .toList())
                        .flatMap(pair -> {
                            Long memberId = pair.getValue();
                            String openId = openIdByKey.get(pair.getKey());

                            if (openId == null) {
                                stats.larkSkip.incrementAndGet();
                                log.warn("[M-SEND][{}] memberId={} — mobile not found in Lark "
                                        + "(check app availability scope / mobile in Lark directory) — skipped", role, memberId);
                                return Mono.empty();
                            }
                            return sendLarkCards(openId, memberId, byPerson.get(memberId), role, today, stats);
                        }, SEND_CONCURRENCY)
                        .then());
    }

    private Mono<Void> sendLarkCards(String openId, Long memberId, List<MaintenanceRecord> records,
                                     Role role, LocalDate today, SendStats stats) {
        List<List<MaintenanceRecord>> pages = partition(records, MAX_RECORDS_PER_CARD);
        int totalPages = pages.size();

        return Flux.range(0, totalPages)
                .concatMap(i -> {
                    int page = i + 1;
                    String card = buildLarkCard(pages.get(i), role, page, totalPages, records.size(), today);
                    String dedupeKey = idempotencyKey(today, role, memberId, page);

                    return larkService.sendCardMessage(openId, card, dedupeKey)
                            .retryWhen(retrySpec("lark memberId=" + memberId + " card=" + page + "/" + totalPages))
                            .doOnSuccess(v -> {
                                stats.larkOk.incrementAndGet();
                                log.info("[M-SEND][{}] ✓ Lark sent memberId={}, card={}/{}, records={}",
                                        role, memberId, page, totalPages, pages.get(page - 1).size());
                            })
                            .onErrorResume(e -> {
                                stats.larkFail.incrementAndGet();
                                log.error("[M-SEND][{}] ✗ Lark FAILED memberId={}, card={}/{}: {}",
                                        role, memberId, page, totalPages, rootMessage(e));
                                return Mono.empty();
                            });
                })
                .then();
    }

    // ======================================================================
    // Content builders
    // ======================================================================

    private String buildEmailContent(List<MaintenanceRecord> records, LocalDate today) {
        StringBuilder html = new StringBuilder();
        html.append("<html><body>")
                .append("<h2>Maintenance Due Date Reminder</h2>")
                .append("<p>The following maintenance tasks are due within the next ").append(DUE_WITHIN_DAYS).append(" days:</p>")
                .append("<table border='1' cellpadding='10' cellspacing='0' style='border-collapse: collapse;'>")
                .append("<thead><tr style='background-color: #f2f2f2;'>")
                .append("<th>Machine Code</th><th>Machine Name</th><th>Due Date</th><th>Days Remaining</th>")
                .append("</tr></thead><tbody>");

        for (MaintenanceRecord r : records) {
            DueStatus s = dueStatus(r.getDueDate(), today);
            html.append("<tr>")
                    .append("<td>").append(escapeHtml(r.getMachineCode())).append("</td>")
                    .append("<td>").append(escapeHtml(r.getMachineName())).append("</td>")
                    .append("<td>").append(r.getDueDate().format(FMT)).append("</td>")
                    .append("<td>").append(s.emoji()).append(' ').append(escapeHtml(s.label())).append("</td>")
                    .append("</tr>");
        }

        html.append("</tbody></table><br>")
                .append("<p><strong>Note:</strong>")
                .append(" 🔴 Overdue or due within ").append(RED_WITHIN_DAYS).append(" days &nbsp;")
                .append(" 🟡 Due within ").append(YELLOW_WITHIN_DAYS).append(" days &nbsp;")
                .append(" 🟢 Due within ").append(DUE_WITHIN_DAYS).append(" days</p>")
                .append("<p>Please ensure these maintenance tasks are completed on time.</p>")
                .append("</body></html>");
        return html.toString();
    }

    private String buildLarkCard(List<MaintenanceRecord> records, Role role,
                                 int page, int totalPages, int totalCount, LocalDate today) {

        String title = totalPages > 1
                ? role.cardTitle + " (" + page + "/" + totalPages + ")"
                : role.cardTitle;

        String summary = "📅 วันที่: " + today.format(FMT) + " | 🔔 รายการที่ต้องดำเนินการ " + totalCount + " รายการ";

        List<String> rows = new ArrayList<>();
        for (MaintenanceRecord r : records) {
            DueStatus s = dueStatus(r.getDueDate(), today);
            String content = "**" + nullToDash(r.getMachineCode()) + " - " + nullToDash(r.getMachineName()) + "**\n"
                    + "<font color='" + s.color() + "'>Due: " + r.getDueDate().format(FMT) + " (" + s.label() + ")</font>";
            rows.add("{\"tag\":\"column_set\",\"flex_mode\":\"none\",\"background_style\":\"grey\","
                    + "\"columns\":[{\"tag\":\"column\",\"width\":\"weighted\",\"weight\":1,"
                    + "\"elements\":[" + markdownElement(content) + "]}]}");
        }

        String legend = "<font color='red'>🔴 Overdue/≤" + RED_WITHIN_DAYS + "วัน</font>  "
                + "<font color='yellow'>🟡 ≤" + YELLOW_WITHIN_DAYS + "วัน</font>  "
                + "<font color='green'>🟢 ≤" + DUE_WITHIN_DAYS + "วัน</font>";

        return "{"
                + "\"config\":{\"wide_screen_mode\":true},"
                + "\"header\":{\"title\":{\"tag\":\"plain_text\",\"content\":\"" + escapeJson(title) + "\"},\"template\":\"green\"},"
                + "\"elements\":["
                + markdownElement(summary) + ","
                + "{\"tag\":\"hr\"},"
                + String.join(",", rows) + ","
                + "{\"tag\":\"hr\"},"
                + markdownElement(legend)
                + "]}";
    }

    private String markdownElement(String content) {
        return "{\"tag\":\"markdown\",\"content\":\"" + escapeJson(content) + "\"}";
    }

    private record DueStatus(String color, String emoji, String label) {}

    private DueStatus dueStatus(LocalDate dueDate, LocalDate today) {
        long daysLeft = ChronoUnit.DAYS.between(today, dueDate);
        if (daysLeft < 0) return new DueStatus("red", "🔴", "เลยกำหนดมา " + Math.abs(daysLeft) + " วัน");

        String label = daysLeft == 0 ? "ครบกำหนดวันนี้" : "อีก " + daysLeft + " วัน";
        if (daysLeft <= RED_WITHIN_DAYS) return new DueStatus("red", "🔴", label);
        if (daysLeft <= YELLOW_WITHIN_DAYS) return new DueStatus("yellow", "🟡", label);
        return new DueStatus("green", "🟢", label);
    }

    // ======================================================================
    // Retry / error helpers
    // ======================================================================

    private RetryBackoffSpec retrySpec(String what) {
        return Retry.backoff(MAX_RETRIES, Duration.ofSeconds(2))
                .maxBackoff(Duration.ofSeconds(10))
                .filter(this::isRetryable)
                .doBeforeRetry(s -> log.warn("[M-RETRY] {} attempt={}/{} cause={}",
                        what, s.totalRetries() + 1, MAX_RETRIES, s.failure().getMessage()));
    }

    private boolean isRetryable(Throwable e) {
        if (e instanceof EmailService.EmailSendException ex) return ex.isRetryable();
        return !(e instanceof IllegalArgumentException);
    }

    private String rootMessage(Throwable e) {
        Throwable t = (Exceptions.isRetryExhausted(e) && e.getCause() != null) ? e.getCause() : e;
        return t.getClass().getSimpleName() + ": " + t.getMessage();
    }

    private static final class SendStats {
        final AtomicInteger emailOk = new AtomicInteger();
        final AtomicInteger emailFail = new AtomicInteger();
        final AtomicInteger emailSkip = new AtomicInteger();
        final AtomicInteger larkOk = new AtomicInteger();
        final AtomicInteger larkFail = new AtomicInteger();
        final AtomicInteger larkSkip = new AtomicInteger();
    }

    // ======================================================================
    // Utils
    // ======================================================================

    /** ขึ้นต้นด้วย "maintenance" เพื่อไม่ชนกับ key ของ Calibration */
    private String idempotencyKey(LocalDate date, Role role, Long memberId, int page) {
        String raw = "maintenance|" + date + "|" + role + "|" + memberId + "|" + page;
        return UUID.nameUUIDFromBytes(raw.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static <T> List<List<T>> partition(List<T> list, int size) {
        List<List<T>> pages = new ArrayList<>();
        for (int i = 0; i < list.size(); i += size) {
            pages.add(list.subList(i, Math.min(i + size, list.size())));
        }
        return pages;
    }

    private String nullToDash(String value) {
        return (value == null || value.isBlank()) ? "-" : value;
    }

    private String escapeJson(String text) {
        if (text == null) return "";
        StringBuilder sb = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.toString();
    }

    private String escapeHtml(String text) {
        if (text == null) return "-";
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private String toInternationalFormat(String raw) {
        if (raw == null) return null;

        String first = Arrays.stream(raw.split("[,;/]"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .findFirst()
                .orElse(null);
        if (first == null) return null;

        String s = first.replaceAll("[\\s\\-().]", "");
        if (s.startsWith("00")) s = "+" + s.substring(2);
        else if (s.startsWith("0")) s = "+66" + s.substring(1);
        else if (s.startsWith("66")) s = "+" + s;

        return s.matches("\\+?\\d{8,15}") ? s : null;
    }

    private String mobileKey(String e164) {
        return e164 == null ? null : e164.replaceAll("\\D", "");
    }

    private enum Role {
        RESPONSIBLE_SUPERVISOR("Maintenance Reminder"),
        MANAGER("Maintenance Reminder (Manager)"),
        DEPARTMENT_ADMIN("Maintenance Reminder (Department Admin)"),
        ADMIN("Maintenance Reminder (Admin)");

        private final String cardTitle;

        Role(String cardTitle) {
            this.cardTitle = cardTitle;
        }
    }
}