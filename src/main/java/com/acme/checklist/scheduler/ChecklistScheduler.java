package com.acme.checklist.scheduler;

import com.acme.checklist.entity.ChecklistRecord;
import com.acme.checklist.entity.Machine;
import com.acme.checklist.service.CommonService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.data.relational.core.query.Criteria;
import org.springframework.data.relational.core.query.Query;
import org.springframework.data.relational.core.query.Update;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class ChecklistScheduler {
    private final R2dbcEntityTemplate template;

    @Scheduled(cron = "0 1 0 * * MON")
    public void updateOverdueChecklistsWeek() {
        Query query = Query.query(
                Criteria.where("checklist_status").in("PENDING SUPERVISOR", "PENDING MANAGER")
                        .and("reset_period").is("WEEKLY")
        );
        template.select(query, ChecklistRecord.class)
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
                .doOnError(e -> log.error("Failed to update overdue weekly checklists: {}", e.getMessage()))
                .subscribe();
    }

    @Scheduled(cron = "0 0 0 1 * *")
    public void updateOverdueChecklistsMonth() {
        Query query = Query.query(
                Criteria.where("checklist_status").in("PENDING SUPERVISOR", "PENDING MANAGER")
                        .and("reset_period").is("MONTHLY")
        );
        template.select(query, ChecklistRecord.class)
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
                .doOnError(e -> log.error("Failed to update overdue monthly checklists: {}", e.getMessage()))
                .subscribe();
    }

}
