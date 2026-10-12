package com.acme.checklist.scheduler;

import com.acme.checklist.entity.Department;
import com.acme.checklist.service.LarkService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.data.domain.Sort;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.data.relational.core.query.Criteria;
import org.springframework.data.relational.core.query.Query;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.Exceptions;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static com.acme.checklist.scheduler.SchedulerLocks.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class DepartmentSyncScheduler {

    private final R2dbcEntityTemplate template;
    private final LarkService larkService;

    private static final int MAX_RETRIES = 3;

    /**
     * Sync แผนกทั้งหมดไป Lark Base ทุกวัน 13:24 เวลาไทย
     * ระบุ zone ชัดเจน → รันเวลาเดิมเสมอ ไม่ว่า server จะตั้ง timezone อะไร
     */
    @Scheduled(cron = "0 24 13 * * *", zone = "Asia/Bangkok")
    @SchedulerLock(name = DEPARTMENT_SYNC_LARK,
            lockAtLeastFor = ASYNC_LOCK_AT_LEAST, lockAtMostFor = ASYNC_LOCK_AT_MOST)
    public void syncDepartmentsToLark() {
        long startedAt = System.currentTimeMillis();
        log.info("[DEPT-SYNC] ▶ start");

        AtomicInteger ok     = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();

        template.select(
                        Query.query(Criteria.empty()).sort(Sort.by(Sort.Direction.ASC, "id")),
                        Department.class
                )
                .collectList()   // อ่านให้ครบก่อน แล้วค่อยเรียก Lark ทีละแผนก
                .flatMapMany(Flux::fromIterable)
                .concatMap(dept -> larkService.upsertDepartmentRecord(dept)
                        .retryWhen(Retry.backoff(MAX_RETRIES, Duration.ofSeconds(2))
                                .maxBackoff(Duration.ofSeconds(10))
                                .doBeforeRetry(s -> log.warn("[DEPT-SYNC] retry dept id={} attempt={}/{} cause={}",
                                        dept.getId(), s.totalRetries() + 1, MAX_RETRIES, s.failure().getMessage())))
                        .doOnSuccess(v -> {
                            ok.incrementAndGet();
                            log.debug("[DEPT-SYNC] ✓ dept id={} name={}", dept.getId(), dept.getDepartment());
                        })
                        .onErrorResume(e -> {
                            failed.incrementAndGet();
                            Throwable cause = (Exceptions.isRetryExhausted(e) && e.getCause() != null) ? e.getCause() : e;
                            log.error("[DEPT-SYNC] ✗ Failed dept id={} name={}: {}",
                                    dept.getId(), dept.getDepartment(), cause.getMessage());
                            return Mono.empty();
                        }))
                .then()
                .subscribe(
                        null,
                        e -> log.error("[DEPT-SYNC] ✗ Failed after {} ms: {}",
                                System.currentTimeMillis() - startedAt, e.getMessage(), e),
                        () -> log.info("[DEPT-SYNC] ■ done in {} ms | ok={} failed={}",
                                System.currentTimeMillis() - startedAt, ok.get(), failed.get())
                );
    }
}