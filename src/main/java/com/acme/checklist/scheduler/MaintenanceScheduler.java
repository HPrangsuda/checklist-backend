package com.acme.checklist.scheduler;

import com.acme.checklist.entity.Machine;
import com.acme.checklist.entity.MaintenanceRecord;
import com.acme.checklist.entity.Member;
import com.acme.checklist.entity.enums.MaintenanceType;
import com.acme.checklist.service.EmailService;
import com.acme.checklist.service.LarkService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.data.relational.core.query.Criteria;
import org.springframework.data.relational.core.query.Query;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
@Component
@RequiredArgsConstructor
public class MaintenanceScheduler {

    private final R2dbcEntityTemplate template;
    private final EmailService emailService;
    private final LarkService larkService;

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    @Scheduled(cron = "0 0 1 25 12 *", zone = "Asia/Bangkok")public void createNextYearMaintenanceRecords() {
        int currentYear = LocalDate.now().getYear();
        int nextYear = currentYear + 1;

        template.getDatabaseClient()
                .sql("SELECT * FROM maintenance_record WHERE years = $1")
                .bind(0, String.valueOf(currentYear))
                .map((row, metadata) -> {
                    MaintenanceRecord r = new MaintenanceRecord();
                    r.setMachineCode(row.get("machine_code", String.class));
                    r.setMachineName(row.get("machine_name", String.class));
                    r.setRound(row.get("round", Integer.class));
                    r.setNote(row.get("note", String.class));
                    String typeStr = row.get("maintenance_type", String.class);
                    r.setMaintenanceType(typeStr != null ? MaintenanceType.valueOf(typeStr) : MaintenanceType.PREVENTIVE);
                    LocalDate dueDate = row.get("due_date", LocalDate.class);
                    r.setDueDate(dueDate != null ? dueDate.withYear(nextYear) : null);
                    LocalDate planDate = row.get("plan_date", LocalDate.class);
                    r.setPlanDate(planDate != null ? planDate.withYear(nextYear) : null);
                    r.setYears(String.valueOf(nextYear));
                    // reset fields
                    r.setStartDate(null);
                    r.setActualDate(null);
                    r.setStatus(null);
                    r.setMaintenanceBy(null);
                    r.setResponsibleMaintenance(null);
                    r.setAttachment(null);
                    return r;
                })
                .all()
                .collectList()
                .flatMap(records -> {
                    if (records.isEmpty()) {
                        log.info("[M-COPY] No maintenance records found for year={} — skipping", currentYear);
                        return Mono.empty();
                    }

                    return template.getDatabaseClient()
                            .sql("SELECT COUNT(*) as cnt FROM maintenance_record WHERE years = $1")
                            .bind(0, String.valueOf(nextYear))
                            .map((row, metadata) -> {
                                Long cnt = row.get("cnt", Long.class);
                                return cnt != null ? cnt : 0L;
                            })
                            .one()
                            .flatMap(count -> {
                                if (count > 0) {
                                    log.warn("[M-COPY] Records for year={} already exist ({} records) — skipping", nextYear, count);
                                    return Mono.empty();
                                }

                                log.info("[M-COPY] Creating {} maintenance records for year={}", records.size(), nextYear);

                                return Flux.fromIterable(records)
                                        .concatMap(r -> {
                                            DatabaseClient.GenericExecuteSpec spec = template.getDatabaseClient()
                                                    .sql("""
                                                            INSERT INTO maintenance_record
                                                            (machine_code, machine_name, years, round, due_date, plan_date,
                                                             note, maintenance_type)
                                                            VALUES ($1, $2, $3, $4, $5, $6, $7, $8)
                                                            """);

                                            spec = bindNullable(spec, 0, r.getMachineCode(), String.class);
                                            spec = bindNullable(spec, 1, r.getMachineName(), String.class);
                                            spec = spec.bind(2, String.valueOf(nextYear));
                                            spec = bindNullable(spec, 3, r.getRound(), Integer.class);
                                            spec = bindNullable(spec, 4, r.getDueDate(), LocalDate.class);
                                            spec = bindNullable(spec, 5, r.getPlanDate(), LocalDate.class);
                                            spec = bindNullable(spec, 6, r.getNote(), String.class);
                                            String maintenanceTypeValue = r.getMaintenanceType() != null
                                                    ? r.getMaintenanceType().name()
                                                    : "PREVENTIVE";
                                            spec = spec.bind(7, maintenanceTypeValue);

                                            return spec.then()
                                                    .doOnSuccess(v -> log.info("[M-COPY] ✓ Inserted machineCode={}, round={}, year={}",
                                                            r.getMachineCode(), r.getRound(), nextYear))
                                                    .doOnError(e -> log.error("[M-COPY] ✗ Insert failed machineCode={}: {}",
                                                            r.getMachineCode(), e.getMessage()));
                                        })
                                        .then();
                            });
                })
                .onErrorResume(e -> {
                    log.error("[M-COPY] Failed to create next year maintenance records: {}", e.getMessage(), e);
                    return Mono.empty();
                })
                .subscribe();
    }

    private <T> DatabaseClient.GenericExecuteSpec bindNullable(
            DatabaseClient.GenericExecuteSpec spec, int index, T value, Class<T> type) {
        return value != null ? spec.bind(index, value) : spec.bindNull(index, type);
    }

    @Scheduled(cron = "0 0 9 * * MON-FRI", zone = "Asia/Bangkok")
    public void sendDailyToResponsibleAndSupervisor() {
        fetchDueMaintenances()
                .collectList()
                .flatMap(records -> {
                    if (records.isEmpty()) return Mono.empty();
                    return fetchMachinesAndGroupByRole(records, Role.RESPONSIBLE_SUPERVISOR);
                })
                .doOnSuccess(v -> log.info("[M-S1] Completed"))
                .doOnError(e -> log.error("[M-S1] Error: {}", e.getMessage(), e))
                .onErrorResume(e -> Mono.empty())
                .subscribe();
    }

    @Scheduled(cron = "0 15 9 * * MON,WED", zone = "Asia/Bangkok")
    public void sendWeeklyToManager() {
        fetchDueMaintenances()
                .collectList()
                .flatMap(records -> {
                    if (records.isEmpty()) return Mono.empty();
                    return fetchMachinesAndGroupByRole(records, Role.MANAGER);
                })
                .doOnSuccess(v -> log.info("[M-S2] Completed"))
                .doOnError(e -> log.error("[M-S2] Error: {}", e.getMessage(), e))
                .onErrorResume(e -> Mono.empty())
                .subscribe();
    }

    private Flux<MaintenanceRecord> fetchDueMaintenances() {
        LocalDate today = LocalDate.now();
        LocalDate in30Days = today.plusDays(30);

        return template.select(
                Query.query(
                        Criteria.where("due_date").lessThanOrEquals(in30Days)
                                .and("start_date").isNull()
                ).sort(Sort.by("due_date").ascending()),
                MaintenanceRecord.class
        ).doOnNext(r -> log.info("[M-FETCH] id={}, machineCode={}, machineName={}, dueDate={}, startDate={}",
                r.getId(), r.getMachineCode(), r.getMachineName(), r.getDueDate(), r.getStartDate()));
    }

    private Mono<Void> fetchMachinesAndGroupByRole(List<MaintenanceRecord> records, Role role) {

        Map<String, List<MaintenanceRecord>> byMachineCode = records.stream()
                .filter(r -> r.getMachineCode() != null)
                .collect(Collectors.groupingBy(MaintenanceRecord::getMachineCode));

        return template.select(
                        Query.query(Criteria.where("machine_code").in(byMachineCode.keySet())),
                        Machine.class
                )
                .collectList()
                .flatMap(machines -> {
                    log.info("[M-GROUP] Found {} machines, role={}", machines.size(), role);

                    Map<Long, Map<Long, MaintenanceRecord>> byPersonMap = new HashMap<>();

                    machines.forEach(machine -> {
                        List<MaintenanceRecord> machineRecords = byMachineCode.getOrDefault(
                                machine.getMachineCode(), List.of()
                        );
                        if (machineRecords.isEmpty()) return;

                        List<Long> personIds = switch (role) {
                            case RESPONSIBLE_SUPERVISOR -> Stream.of(
                                            machine.getResponsiblePersonId(),
                                            machine.getSupervisorId()
                                    )
                                    .filter(Objects::nonNull)
                                    .distinct()
                                    .toList();

                            case MANAGER -> Stream.of(machine.getManagerId())
                                    .filter(Objects::nonNull)
                                    .toList();
                        };

                        log.info("[M-GROUP] machineCode={}, personIds={}", machine.getMachineCode(), personIds);

                        personIds.forEach(personId -> {
                            Map<Long, MaintenanceRecord> existing = byPersonMap.computeIfAbsent(
                                    personId, k -> new LinkedHashMap<>()
                            );
                            machineRecords.forEach(r -> existing.put(r.getId(), r));
                        });
                    });

                    Map<Long, List<MaintenanceRecord>> byPerson = byPersonMap.entrySet().stream()
                            .collect(Collectors.toMap(
                                    Map.Entry::getKey,
                                    e -> new ArrayList<>(e.getValue().values())
                            ));

                    log.info("[M-GROUP] Grouped into {} persons", byPerson.size());
                    if (byPerson.isEmpty()) return Mono.empty();

                    return fetchMembersAndSend(byPerson, role);
                });
    }

    private Mono<Void> fetchMembersAndSend(Map<Long, List<MaintenanceRecord>> byPerson, Role role) {

        Set<Long> memberIds = byPerson.keySet();
        log.info("[M-SEND] Fetching members for {} memberIds: {}", memberIds.size(), memberIds);

        return template.select(
                        Query.query(Criteria.where("id").in(memberIds)),
                        Member.class
                )
                .collectList()
                .flatMap(members -> {
                    log.info("[M-SEND] Found {} members", members.size());
                    if (members.isEmpty()) return Mono.empty();

                    // --- Email ---
                    Mono<Void> emailMono = Flux.fromIterable(members)
                            .filter(m -> m.getEmail() != null && !m.getEmail().isBlank())
                            .flatMap(member -> {
                                List<MaintenanceRecord> empRecords = byPerson.getOrDefault(
                                        member.getId(), List.of()
                                );
                                if (empRecords.isEmpty()) return Mono.empty();

                                String subject = "Maintenance Due Date Reminder - "
                                        + LocalDate.now().format(DateTimeFormatter.ISO_DATE);
                                String emailContent = buildEmailContent(empRecords);

                                log.info("[M-SEND] → Email to memberId={}, email={}, records={}",
                                        member.getId(), member.getEmail(), empRecords.size());

                                return emailService.sendReminder(member.getEmail(), subject, emailContent)
                                        .doOnSuccess(v -> log.info("[M-SEND] ✓ Email sent to memberId={}", member.getId()))
                                        .onErrorResume(e -> {
                                            log.warn("[M-SEND] ✗ Email failed memberId={}: {}", member.getId(), e.getMessage());
                                            return Mono.empty();
                                        });
                            })
                            .then();

                    // --- Lark ---
                    Map<String, Long> mobileToMemberId = new HashMap<>();
                    members.stream()
                            .filter(m -> m.getMobiles() != null && !m.getMobiles().isBlank())
                            .forEach(m -> mobileToMemberId.put(toInternationalFormat(m.getMobiles()), m.getId()));

                    Mono<Void> larkMono = mobileToMemberId.isEmpty()
                            ? Mono.fromRunnable(() -> log.warn("[M-SEND] No valid mobiles — skipping Lark"))
                            : larkService.batchGetOpenIdsByMobile(new ArrayList<>(mobileToMemberId.keySet()))
                            .flatMap(openIdMap -> {
                                if (openIdMap.isEmpty()) return Mono.empty();

                                return Flux.fromIterable(mobileToMemberId.entrySet())
                                        .flatMap(entry -> {
                                            String mobile = entry.getKey();
                                            Long memberId = entry.getValue();
                                            String openId = openIdMap.get(mobile);

                                            if (openId == null) return Mono.empty();

                                            List<MaintenanceRecord> empRecords = byPerson.getOrDefault(memberId, List.of());
                                            if (empRecords.isEmpty()) return Mono.empty();

                                            String message = buildMessage(empRecords, role);
                                            return larkService.sendCardMessage(openId, message)
                                                    .doOnSuccess(v -> log.info("[M-SEND] ✓ Lark sent to memberId={}", memberId))
                                                    .onErrorResume(e -> Mono.empty());
                                        })
                                        .then();
                            });

                    return Mono.when(emailMono, larkMono);
                });
    }

    private String buildEmailContent(List<MaintenanceRecord> maintenances) {
        StringBuilder content = new StringBuilder();
        content.append("<html><body>");
        content.append("<h2>Maintenance Due Date Reminder</h2>");
        content.append("<p>The following maintenance are due within the next 30 days:</p>");
        content.append("<table border='1' cellpadding='10' cellspacing='0' style='border-collapse: collapse;'>");
        content.append("<thead>");
        content.append("<tr style='background-color: #f2f2f2;'>");
        content.append("<th>Machine Code</th>");
        content.append("<th>Machine Name</th>");
        content.append("<th>Due Date</th>");
        content.append("<th>Days Remaining</th>");
        content.append("</tr>");
        content.append("</thead>");
        content.append("<tbody>");

        LocalDate today = LocalDate.now();

        for (MaintenanceRecord r : maintenances) {
            long daysRemaining = ChronoUnit.DAYS.between(today, r.getDueDate());

            String statusLabel;
            if (daysRemaining < 0) {
                statusLabel = "🔴 เลยกำหนดมา " + Math.abs(daysRemaining) + " วัน";
            } else if (daysRemaining <= 7) {
                statusLabel = "🔴 อีก " + daysRemaining + " วัน";
            } else if (daysRemaining <= 14) {
                statusLabel = "🟡 อีก " + daysRemaining + " วัน";
            } else {
                statusLabel = "🟢 อีก " + daysRemaining + " วัน";
            }

            content.append("<tr>");
            content.append("<td>").append(escapeHtml(r.getMachineCode())).append("</td>");
            content.append("<td>").append(escapeHtml(r.getMachineName())).append("</td>");
            content.append("<td>").append(r.getDueDate().format(FMT)).append("</td>");
            content.append("<td>").append(statusLabel).append("</td>");
            content.append("</tr>");
        }

        content.append("</tbody>");
        content.append("</table>");
        content.append("<br>");
        content.append("<p><strong>Note:</strong>");
        content.append(" <span style='padding:2px 6px;'>🔴</span> Overdue or due within 7 days &nbsp;");
        content.append(" <span style='padding:2px 6px;'>🟡</span> Due within 14 days &nbsp;");
        content.append(" <span style='padding:2px 6px;'>🟢</span> Due within 30 days</p>");
        content.append("<p>Please ensure these maintenances are completed on time.</p>");
        content.append("</body></html>");

        return content.toString();
    }

    private String buildMessage(List<MaintenanceRecord> records, Role role) {
        LocalDate today = LocalDate.now();

        String header = switch (role) {
            case RESPONSIBLE_SUPERVISOR -> "Maintenance Reminder";
            case MANAGER -> "Maintenance Reminder (Manager)";
        };

        StringBuilder elements = new StringBuilder();

        records.forEach(r -> {
            long daysLeft = ChronoUnit.DAYS.between(today, r.getDueDate());

            String color;
            String daysLabel;

            if (daysLeft < 0) {
                color = "red";
                daysLabel = "เลยกำหนดมา " + Math.abs(daysLeft) + " วัน";
            } else if (daysLeft <= 7) {
                color = "red";
                daysLabel = "อีก " + daysLeft + " วัน";
            } else if (daysLeft <= 14) {
                color = "yellow";
                daysLabel = "อีก " + daysLeft + " วัน";
            } else {
                color = "green";
                daysLabel = "อีก " + daysLeft + " วัน";
            }

            elements.append("""
                    {
                           "tag": "column_set",
                           "flex_mode": "none",
                           "background_style": "grey",
                           "columns": [
                               {
                                   "tag": "column",
                                   "width": "weighted",
                                   "weight": 1,
                                   "elements": [
                                       {
                                           "tag": "markdown",
                                           "content": "**%s - %s**\\n<font color='%s'>Due: %s (%s)</font>"
                                       }
                                   ]
                               }
                           ]
                       },
               """.formatted(
                    escapeJson(r.getMachineCode()),
                    escapeJson(r.getMachineName()),
                    color,
                    r.getDueDate().format(FMT),
                    daysLabel
            ));
        });

        String elementsStr = elements.toString().trim();
        if (elementsStr.endsWith(",")) {
            elementsStr = elementsStr.substring(0, elementsStr.length() - 1);
        }

        return """
                {
                  "config": { "wide_screen_mode": true },
                  "header": {
                    "title": { "tag": "plain_text", "content": "%s" },
                    "template": "green"
                  },
                  "elements": [
                    {
                      "tag": "markdown",
                      "content": "📅 วันที่: %s | 🔔 รายการที่ต้องดำเนินการ %d รายการ"
                    },
                    { "tag": "hr" },
                    %s,
                    { "tag": "hr" },
                    {
                      "tag": "markdown",
                      "content": "<font color='red'>🔴 Overdue/≤7วัน</font>  <font color='yellow'>🟡 ≤14วัน</font>  <font color='green'>🟢 ≤30วัน</font>"
                    }
                  ]
                }
                """.formatted(
                escapeJson(header),
                today.format(FMT),
                records.size(),
                elementsStr
        );
    }

    private String escapeJson(String text) {
        if (text == null) return "";
        return text.replace("\\", "\\\\")
                .replace("\"", "\\\"");
    }

    private String escapeHtml(String text) {
        if (text == null) return "-";
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private String toInternationalFormat(String mobile) {
        if (mobile.startsWith("0")) return "+66" + mobile.substring(1);
        if (mobile.startsWith("66") && !mobile.startsWith("+66")) return "+" + mobile;
        return mobile;
    }

    private enum Role {
        RESPONSIBLE_SUPERVISOR,
        MANAGER
    }
}