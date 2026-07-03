package com.acme.checklist.scheduler;

import com.acme.checklist.entity.Department;
import com.acme.checklist.service.LarkService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Sort;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.data.relational.core.query.Criteria;
import org.springframework.data.relational.core.query.Query;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class DepartmentSyncScheduler {

    @Autowired
    private R2dbcEntityTemplate template;

    @Autowired
    private LarkService larkService;

    @Scheduled(cron = "0 24 13 * * *")
    public void syncDepartmentsToLark() {
        log.info("=== [DepartmentSync] Start syncing departments to Lark ===");
        template.select(
                        Query.query(Criteria.empty())
                                .sort(Sort.by(Sort.Direction.ASC, "id")),
                        Department.class
                )
                .concatMap(dept -> larkService.upsertDepartmentRecord(dept)
                        .doOnSuccess(v -> log.info("[DepartmentSync] Synced dept id={}, name={}",
                                dept.getId(), dept.getDepartment()))
                        .doOnError(e -> log.error("[DepartmentSync] Failed dept id={}, name={} : {}",
                                dept.getId(), dept.getDepartment(), e.getMessage()))
                        .onErrorComplete()
                )
                .doOnComplete(() -> log.info("=== [DepartmentSync] Done ==="))
                .subscribe();
    }
}